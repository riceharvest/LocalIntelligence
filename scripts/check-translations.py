#!/usr/bin/env python3
"""Checks every app/src/main/res/values-*/strings.xml against values/strings.xml.

WHY: translators (and machine translation) break strings in ways aapt only
reports much later, or never: a dropped placeholder, a bare percent sign, an
unescaped apostrophe. This runs in seconds with no dependencies, so a bad
translation is caught before a build.

Fails (exit 1) on: a missing or extra key, a placeholder mismatch, a bare "%"
(outside formatted="false" strings), an unescaped apostrophe or quote, invalid
XML, and a plural with no "other" quantity. Warns on a value that is still the
English text (longer than 12 characters, not in ALLOWED_ENGLISH).
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
BASE = RES / "values" / "strings.xml"

# Values that are meant to be identical in every language (brand names, units, loanwords).
ALLOWED_ENGLISH = {
    "app_name",
}

PLACEHOLDER = re.compile(r"%\d+\$[sd]")
PLACEHOLDER_OR_PERCENT = re.compile(r"%%|%\d+\$[sd]")


def load(path):
    """Return {key: (kind, value)} where kind is 'string', 'plurals' or 'array'.

    A plurals value is {quantity: text}; an array value is [text, ...].
    Raises ET.ParseError on invalid XML.
    """
    root = ET.parse(path).getroot()
    out = {}
    for el in root:
        name = el.get("name")
        if el.tag == "string" and name:
            out[name] = ("string", el.text or "", el.get("formatted") == "false", el.get("translatable") == "false")
        elif el.tag == "plurals" and name:
            out[name] = ("plurals", {i.get("quantity"): i.text or "" for i in el.findall("item")}, False, el.get("translatable") == "false")
        elif el.tag == "string-array" and name:
            out[name] = ("array", [i.text or "" for i in el.findall("item")], False, el.get("translatable") == "false")
    return out


def placeholders(text):
    return set(PLACEHOLDER.findall(text))


def bad_percent(text):
    """True if a '%' is left after the valid '%%' and '%N$s' / '%N$d' forms are taken out."""
    return "%" in PLACEHOLDER_OR_PERCENT.sub("", text)


def bad_quotes(text):
    """True if an apostrophe or quote would break aapt.

    An escaped \\' or \\\" is fine. A quote opens a literal run that must close,
    so an odd count of unescaped double quotes is an error. An apostrophe is an
    error unless it sits inside such a double-quoted run.
    """
    unescaped = re.sub(r"\\.", "", text)
    if unescaped.count('"') % 2:
        return True
    outside = re.sub(r'"[^"]*"', "", unescaped)
    return "'" in outside


def check_text(key, text, english, formatted, errors, warnings):
    if placeholders(text) != placeholders(english):
        errors.append(f"{key}: placeholders {sorted(placeholders(text))} differ from English {sorted(placeholders(english))}")
    if not formatted and bad_percent(text):
        errors.append(f"{key}: bare '%' (write '%%' or use formatted=\"false\")")
    if bad_quotes(text):
        errors.append(f"{key}: unescaped apostrophe or quote (write \\' or \\\")")


def check_language(lang_path, base):
    errors, warnings = [], []
    try:
        mine = load(lang_path)
    except ET.ParseError as e:
        return [f"invalid XML: {e}"], []
    for key in base:
        if key not in mine:
            errors.append(f"missing key: {key}")
    for key in mine:
        if key not in base:
            errors.append(f"extra key: {key}")
    for key, (kind, value, formatted, untranslatable) in base.items():
        if untranslatable or key not in mine:
            continue
        mkind, mvalue, mformatted, _ = mine[key]
        if mkind != kind:
            errors.append(f"{key}: is a {mkind}, English is a {kind}")
            continue
        if kind == "string":
            check_text(key, mvalue, value, mformatted or formatted, errors, warnings)
            if mvalue == value and len(value) > 12 and key not in ALLOWED_ENGLISH:
                warnings.append(f"{key}: still the English text")
        elif kind == "plurals":
            if "other" not in mvalue:
                errors.append(f"{key}: no 'other' quantity")
            for quantity, text in mvalue.items():
                check_text(f"{key}[{quantity}]", text, value.get("other", ""), mformatted or formatted, errors, warnings)
            if "other" in mvalue and mvalue["other"] == value.get("other") and len(value.get("other", "")) > 12:
                warnings.append(f"{key}: still the English text")
        elif kind == "array":
            if len(mvalue) != len(value):
                errors.append(f"{key}: {len(mvalue)} items, English has {len(value)}")
    return errors, warnings


def main():
    try:
        base = load(BASE)
    except ET.ParseError as e:
        print(f"English base is invalid XML: {e}")
        return 1
    failed = False
    langs = sorted(RES.glob("values-*/strings.xml"))
    for path in langs:
        errors, warnings = check_language(path, base)
        lang = path.parent.name.removeprefix("values-")
        status = "FAIL" if errors else "OK"
        print(f"{lang}: {status}, {len(errors)} error(s), {len(warnings)} warning(s)")
        for e in errors:
            print(f"  error: {e}")
        for w in warnings:
            print(f"  warning: {w}")
        failed = failed or bool(errors)
    if not langs:
        print("no values-*/strings.xml found")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

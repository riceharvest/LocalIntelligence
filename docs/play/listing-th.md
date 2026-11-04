# Play store listing (drafts)

Draft text only. Nothing is submitted. Source for the rules: the store-listing page
https://support.google.com/googleplay/android-developer/answer/9866151 (named in
docs/roadmap.md, "Before public release (v1.0)", Store listing).

Facts used below come from the code and the privacy page. Anything else is marked UNCONFIRMED.

App name: **LocalIntelligence** (unchanged).

---

## ไทย

**คำอธิบายสั้น** (ไม่เกิน 80 ตัวอักษร; 71 ตัวอักษร)

> ผู้ช่วย AI ส่วนตัวที่รันโมเดลบนโทรศัพท์ของคุณ ไม่ต้องมีบัญชี ไม่มีโฆษณา

**คำอธิบายเต็ม**

LocalIntelligence เป็นผู้ช่วย AI สำหรับ Android ที่รันโมเดลภาษาบนโทรศัพท์ของคุณ

เลือกโมเดลที่คุณมีอยู่แล้ว หรือดาวน์โหลดโมเดลที่แนะนำ ในโหมดง่าย แอปจะแสดงเฉพาะโมเดลที่แนะนำ โหมดเทคนิคจะเพิ่มโมเดลอื่นจากคลังของ Hugging Face และให้คุณใส่คีย์ของตัวเองสำหรับบริการระยะไกลได้

สิ่งที่คุณขอให้ทำได้ เมื่อคุณขอ:
- ตอบคำถามและทำงานหลายขั้นตอนในแชท
- ตั้งการปลุกและตัวจับเวลา ดูหรือเพิ่มกิจกรรมในปฏิทิน
- ค้นหารายชื่อติดต่อ เปิดแอป อ่านไฟล์ที่คุณเลือก หรือตรวจแบตเตอรี่
- อ่านการแจ้งเตือนที่คุณอนุญาตให้เห็น และตอบกลับได้
- อ่านข้อความและการโทรล่าสุด และส่งข้อความ เฉพาะเมื่อคุณตั้ง LocalIntelligence เป็นแอปผู้ช่วยดิจิทัลหลัก การส่งข้อความจะถามคุณก่อนเสมอ
- พิมพ์ด้วยเสียงในแชท ส่งรูป หรือบันทึกคลิปเสียง หากโมเดลรองรับ
- เปิดคำปลุกในการตั้งค่า เพื่อให้แอปเปิดแบบแฮนด์ฟรี

สิ่งที่ออกจากโทรศัพท์จะปิดอยู่จนกว่าคุณจะตั้งค่า และทุกขั้นตอนที่ใช้เครือข่ายจะถามก่อน ได้แก่ การค้นหาเว็บและการอ่านหน้าเว็บ อากาศ การค้นหา Wikipedia และสถานที่ และผู้ให้บริการโมเดลระยะไกลที่คุณใส่คีย์ของตัวเอง นโยบายความเป็นส่วนตัวระบุแต่ละอย่างและข้อมูลที่แต่ละอย่างได้รับ

ความเป็นส่วนตัว: ไม่มีบัญชี ไม่มีการวิเคราะห์ ไม่มีรายงานการขัดข้อง ไม่มีโฆษณา แชทและสิ่งที่ผู้ช่วยจำไว้จะอยู่บนโทรศัพท์ และปิดการสำรองข้อมูลของ Android สำหรับแอปนี้ คุณรายงานคำตอบให้ผู้พัฒนาได้จากตัวคำตอบเอง โดยเลือกเหตุผล เพิ่มหมายเหตุได้ และตัดข้อความคำตอบออกได้

นโยบายความเป็นส่วนตัว: https://riceharvest.github.io/LocalIntelligence/privacy.html

**หมวดหมู่:** ประสิทธิภาพการทำงาน (Productivity) (เสนอโดยร่างนี้; UNCONFIRMED เจ้าของจะเลือกใน Play Console)

**ข้อมูลติดต่อ**
- เว็บไซต์: ไม่มี
- ช่องทางติดต่อ: GitHub issues, https://github.com/riceharvest/LocalIntelligence/issues
- อีเมล: OPEN Play Console ขออีเมลในหน้าลิสติง ร่างนี้ไม่ได้ใส่ให้ ใช้อีเมลโปรเจกต์ที่เจ้าของเลือก ห้ามใช้อีเมลส่วนตัว

---

## Checks

Short description counted with Python: 71 characters (Thai). The English and Dutch drafts are in docs/play/listing.md.

Claims kept out on purpose: speed, memory use, answer quality, "works offline", and any
comparison with other apps. None of them has been measured on a device (docs/roadmap.md,
docs/measurements.md).

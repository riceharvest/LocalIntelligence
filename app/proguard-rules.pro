## R8 / ProGuard rules for the release build.
##
## Everything below protects a class that is looked up BY NAME rather than
## through a reference the compiler can see. Debug builds never notice any of
## it, because `isMinifyEnabled` is only set on release: in debug R8 does not
## run, nothing is renamed, and every reflective lookup happens to still work.
## A green debug build is therefore not evidence about any rule in this file.

# Room reads generated schema classes reflectively, and kotlinx.serialization
# stores the class's own serial name in the data it writes. Both break silently
# in release if the name is allowed to move.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod


################################################################################
## The one that was actually broken
##
## `AppContainer.SessionStoreFactory` (`:app`) cannot name the `:android` Room
## factory at compile time, because `:android` declares Room as `implementation`
## and `RoomDatabase` is off `:app`'s classpath. It resolves it by name instead:
##
##     Class.forName("dev.localintelligence.android.data.SessionStoresKt")
##         .getDeclaredMethod("durableSessionStore", Context::class.java)
##
## Release build, before this rule (from app/build/outputs/mapping/release/
## mapping.txt, built off origin/main):
##
##     dev.localintelligence.android.data.SessionStoresKt -> r4.m:
##
## `Class.forName` then throws `ClassNotFoundException`, the `catch` in
## `SessionStoreFactory.provide` swallows it, and the app logs "No durable
## session store; conversation is process-local" and runs with no persistence at
## all. The conversation is silently lost on every process death — and only in
## release, which is the build that ships.
##
## BOTH names have to be pinned. The class name is what `Class.forName` looks
## up, and the *method* name is what `getDeclaredMethod` looks up; a facade
## class kept with an obfuscated method still fails, one with a kept method and
## an obfuscated class fails too.
-keep class dev.localintelligence.android.data.SessionStoresKt {
    public static *** durableSessionStore(android.content.Context);
}


################################################################################
## Room's generated schema implementation
##
## `Room.databaseBuilder(...).build()` resolves the generated implementation as
## `Class.forName(databaseClass.getCanonicalName() + "_Impl")`. So the database
## class must keep its name (that name is the lookup key) and the generated
## implementation must keep both its name and its no-arg constructor.
##
## This currently also works via the consumer rules bundled inside
## room-runtime's AAR. It is written out here anyway: an explicit contract in
## this repo is worth more than a transitive dependency's rule file, which can
## be dropped by a version bump or disabled by `consumerProguardFiles`
## changing, and the failure mode is a release build that installs and then
## throws on first database access.
-keep class dev.localintelligence.android.data.LocalIntelligenceDatabase {
    public <init>();
}
-keep class dev.localintelligence.android.data.LocalIntelligenceDatabase_Impl {
    public <init>();
}


################################################################################
## Manifest-declared components
##
## AGP generates equivalent rules into the merged manifest's aapt rules, so
## these are already covered. They are restated because they are load-bearing
## and a generated file is the wrong place to learn that from: if any of these
## names moves, the component it names in the manifest stops existing as far as
## the system is concerned, and the failure is a component that is never
## launched with no error anywhere in the log.
##
## Verified in the release mapping: all nine keep their names.
-keep class dev.localintelligence.app.LocalIntelligenceApp
-keep class dev.localintelligence.app.MainActivity
-keep class dev.localintelligence.app.ui.PermissionActivity
-keep class dev.localintelligence.app.BackgroundAccessActivity
-keep class dev.localintelligence.app.ExecutionService
-keep class dev.localintelligence.app.execution.ScheduledTaskFireReceiver
-keep class dev.localintelligence.android.tools.alarm.AlarmFireReceiver
-keep class dev.localintelligence.android.tools.alarm.AlarmBootReceiver
-keep class dev.localintelligence.android.tools.notifications.LocalNotificationListenerService


################################################################################
## JNI
##
## Deliberately NOT repeated here. `:android` declares
## `-keepclasseswithmembernames class dev.localintelligence.android.inference.**
## { native <methods>; }` in its own `consumer-rules.pro`, R8 applies a
## library's consumer rules to the consuming app automatically, and the release
## dex confirms it took effect: every `native*` method on `LlamaBridge` is
## present under its original name. A second copy in this file would be a
## second place to forget to update.


################################################################################
## Persisted kotlinx.serialization models
##
## A sealed hierarchy's discriminator IS the subclass's serial name, and the
## serial name is the class's own fully-qualified name. R8's bundled
## kotlinx.serialization rules keep these classes but explicitly permit
## obfuscation, which is correct for a value that only has to round-trip inside
## one process and wrong for the schedules and run history in SharedPreferences
## and on disk: a JSON blob written by one build is then undecodable by the
## next, and `ScheduledTaskStore.all()` drops unparseable rows by design.
##
## `-keepnames` rather than `-keep`: the classes stay shrinkable, their names
## simply stop moving.
-keepnames @kotlinx.serialization.Serializable class dev.localintelligence.app.data.**
-keepnames @kotlinx.serialization.Serializable class dev.localintelligence.core.metrics.**
-keepnames @kotlinx.serialization.Serializable class dev.localintelligence.core.agent.**
-keepnames @kotlinx.serialization.Serializable class dev.localintelligence.core.hub.**

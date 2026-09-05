# Add project specific ProGuard rules here.
# FOUNDATION: minifyEnabled is off for debug/release by default; feature agents
# may enable it later. Keep rules conservative in the meantime.

-keepattributes Signature
-keepattributes *Annotation*

# WebRTC
-keep class org.webrtc.** { *; }

# Firebase / Firestore model classes (data classes with no-arg constructors)
-keepclassmembers class com.kin.familyhealth.** {
    <init>();
}

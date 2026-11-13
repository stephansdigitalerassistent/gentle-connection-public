# Proguard / R8 keep rules for com.sun.mail (android-mail) and javax.activation
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }

-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**

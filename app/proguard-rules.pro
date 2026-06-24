# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Koin
-keep class org.koin.** { *; }

# App models — prevent Firestore from stripping field names
-keep class com.textgate.app.data.model.** { *; }
-keepclassmembers class com.textgate.app.data.model.** { *; }

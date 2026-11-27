# Keep kotlinx.serialization generated serializers for WidgetConfig/WidgetAppearance.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

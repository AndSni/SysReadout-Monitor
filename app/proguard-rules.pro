# R8 shrinks and optimizes the release build; names stay as in the source, so a
# stack trace from a user's bug report reads like the code without a mapping file.
-dontobfuscate

# Shizuku starts the helper in its own process from the class name we pass it
# (ShizukuBridge), calling the constructor by reflection: nothing in our code does.
-keep class com.asnidev.sysreadoutmonitor.monitor.ShellService { <init>(...); }

# argon2kt 1.6.0 ships no consumer rules; its JNI layer binds native methods
# and builds result objects by name, so its classes must keep their names.
-keep class com.lambdapioneer.argon2kt.** { *; }

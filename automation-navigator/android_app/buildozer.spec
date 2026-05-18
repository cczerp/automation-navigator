[app]
title = Accessibility Navigator
package.name = accessibilitynavigator
package.domain = org.accessibility.navigator

source.dir = .
source.include_exts = py,png,jpg,jpeg,json,kv,atlas

version = 1.0.0

# Core dependencies — keep this list minimal for a reliable build
requirements = python3,kivy==2.3.1,requests,plyer,certifi,urllib3

# Portrait only — easier for elderly users to hold steady
orientation = portrait
fullscreen = 0

# Android permissions
# INTERNET        — talk to the PC server over Wi-Fi
# READ/WRITE_EXTERNAL_STORAGE — pick and store template images
android.permissions = INTERNET,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE

android.api = 33
android.minapi = 26
android.ndk = 25b
android.ndk_api = 21
android.private_storage = True

# Gradle / build settings
android.enable_androidx = True

# p4a 2026.05+ requires explicit consent when minsdk != ndk_api
p4a.extra_args = --allow-minsdk-ndkapi-mismatch

[buildozer]
log_level = 2
warn_on_root = 1

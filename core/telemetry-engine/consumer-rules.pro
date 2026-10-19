# protobuf-lite finds each message's fields reflectively by name, so R8 must not rename or remove them. `{ *; }` keeps
# whole classes, including messages and fields that only the dump parser tests read; that size cost is accepted
# (kept, audit §3).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

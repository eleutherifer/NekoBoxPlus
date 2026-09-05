# byedpi patches

Adds the Android JNI API used to link libcore with ByeDPI, including the private
socketpair-based TCP/UDP bridge that avoids exposing an IP listener. Applied
automatically via `get_source.sh`.
Target patch commit: ba532298de7b28cfe854aea83d061369d13ca290

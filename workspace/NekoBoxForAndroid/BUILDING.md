# NekoBoxForAndroid

Can be built using gradle or Android Studio.

# libcore

Should be built in Docker using:
```bash
bash buildScript/lib/core.docker.sh
```

It uses patched Go runtime which fixes `PersistentKeepalive` functionality for both Wireguard and AmneziaWG on old kernels (4.17 and older) which is very important in this project.

# sing-box

Uses patched `amneziawg-go`. Read `patches` directory there and proceed using instructions there.

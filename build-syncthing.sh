#!/bin/bash -e

# Build the syncthing library
ORIG=$(pwd)
mkdir -p bin

# Load submodules
if [ ! -f "ext/syncthing/src/github.com/syncthing/syncthing/.git" ]; then
        git submodule update --init --recursive
fi

# Check for GOLANG installation
if [ -z $GOROOT ] || [[ $(go version) != go\ version\ go1.4* ]] ; then
        mkdir -p "build"
        tmpgo='build/go'
        if [ ! -f "$tmpgo/bin/go" ]; then
                # Download GOLANG v1.4
                wget -O go.src.tar.gz http://golang.org/dl/go1.4.src.tar.gz
                sha1=$(sha1sum go.src.tar.gz)
                if [ "$sha1" != "6a7d9bd90550ae1e164d7803b3e945dc8309252b  go.src.tar.gz" ]; then
                        echo "go.src.tar.gz SHA1 checksum does not match!"
                        exit 1
                fi
                mkdir -p $tmpgo
                tar -xzf go.src.tar.gz --strip=1 -C $tmpgo
                rm go.src.tar.gz
                # Build GO for host
                pushd $tmpgo/src
                ./make.bash --no-clean
                popd
        fi
        # Add GO to the environment
        export GOROOT="$(pwd)/$tmpgo"
fi

# Add GO compiler to PATH
export PATH=$GOROOT/bin:$PATH

# Check whether GOLANG is compiled with cross-compilation for android x86 Requires NDK with gcc.
ndkx86=$(pwd)/build/ndk-x86
if [ ! -f $ndkx86/i686-linux-android/bin/gcc ]; then
        $ANDROID_NDK/build/tools/make-standalone-toolchain.sh --platform=android-9 --install-dir=$ndkx86 --arch=x86 --toolchain=x86-4.8
        pushd $GOROOT
        # Fix missing header in toolchain for android 386 with Go1.4
        echo '#include "defs_linux_386.h"' > src/runtime/defs_android_386.h
        cat << 'EOF' > defs_android_386.patch
--- a/src/runtime/os_android.c
+++ b/src/runtime/os_android.c
@@ -11,6 +11,14 @@
 // Used by the app package to start the Go runtime after loading
 // a shared library via JNI. See golang.org/x/mobile/app.
+#ifdef GOARCH_arm
 void _rt0_arm_linux1();
 #pragma cgo_export_static _rt0_arm_linux1
 #pragma cgo_export_dynamic _rt0_arm_linux1
+#endif
+
+#ifdef GOARCH_386
+void _rt0_386_linux();
+#pragma cgo_export_static _rt0_386_linux
+#pragma cgo_export_dynamic _rt0_386_linux
+#endif
EOF
        patch -p1 < defs_android_386.patch
        popd
        pushd $GOROOT/src
        # KNOWN TO FAIL: https://github.com/MarinX/godroid
        set +e
        CC_FOR_TARGET=$ndkx86/bin/i686-linux-android-gcc GOOS=android GOARCH=386 GO386=387 ./make.bash --no-clean
        set -e
        popd
fi

# Check whether GOLANG is compiled with cross-compilation for android ARM. Requires NDK with gcc.
ndkarm=$(pwd)/build/ndk-arm
if [ ! -f $ndkarm/arm-linux-androideabi/bin/gcc ]; then
        ABI=arch-arm $ANDROID_NDK/build/tools/make-standalone-toolchain.sh --platform=android-9 --install-dir=$ndkarm --arch=arm
        pushd $GOROOT/src
        # KNOWN TO FAIL: https://github.com/MarinX/godroid
        set +e
        CC_FOR_TARGET=$ndkarm/bin/arm-linux-androideabi-gcc GOOS=android GOARCH=arm GOARM=5 ./make.bash --no-clean
        set -e
        popd
fi

# Setup GOPATH
cd "ext/syncthing/"
export GOPATH="$(pwd)"

## Install godep
$GOROOT/bin/go get github.com/tools/godep
export PATH="$(pwd)/bin":$PATH

## Setup syncthing and clean
export ENVIRONMENT=android
cd src/github.com/syncthing/syncthing
$GOROOT/bin/go run build.go clean

# x86-Android
SYSROOT=$ndkx86/x86-linux-androideabi PATH=$ndkx86/x86-linux-androideabi/bin:$PATH \
	CGO_ENABLED=1 $GOROOT/bin/go run build.go -goos android -goarch 386 -no-upgrade build -ldflags "-shared"
mv syncthing $ORIG/bin/syncthing-x86
$GOROOT/bin/go run build.go clean

# ARM-Android
PATH=$ndkarm/arm-linux-androideabi/bin:$PATH CGO_ENABLED=1 $GOROOT/bin/go run build.go -goos android -goarch arm -no-upgrade build
mv syncthing $ORIG/bin/syncthing-armeabi
$GOROOT/bin/go run build.go clean



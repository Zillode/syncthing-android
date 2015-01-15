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

# Check whether GOLANG is compiled with cross-compilation for android. Requires NDK with gcc.
tmpndk=$(pwd)/build/ndk
if [ ! -f $tmpndk/arm-linux-androideabi/bin/gcc ]; then
        $ANDROID_NDK/build/tools/make-standalone-toolchain.sh --platform=android-9 --install-dir=$tmpndk
        pushd $GOROOT/src
        # KNOWN TO FAIL: https://github.com/MarinX/godroid
        set +e
        C_FOR_TARGET=$NDK_CC GOOS=android GOARCH=arm GOARM=5 ./make.bash
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

# X86 (Not supported by Go1.4 ?)
#$GOROOT/bin/go run build.go -goos android -goarch 386 -no-upgrade build
#mv syncthing $ORIG/bin/syncthing-x86
#$GOROOT/bin/go run build.go clean

# ARM-Android
PATH=$ndkarm/arm-linux-androideabi/bin:$PATH CGO_ENABLED=1 $GOROOT/bin/go run build.go -goos android -goarch arm -no-upgrade build
mv syncthing $ORIG/bin/syncthing-armeabi
$GOROOT/bin/go run build.go clean



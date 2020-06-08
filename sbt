#!/bin/bash

### Start ejv

# Embeddable Java Version
# Source: https://github.com/jamesward/ejv
#
#  Projects can specify their Java version via a system.properties file
#  Like: java.runtime.version=1.8.0_202
#  That JDK is then downloaded (if needed) and the JAVA_HOME & PATH are set accordingly
#

BASE_LOCAL_DIR=$HOME/.ejv
JQ_VERSION=1.4

OS_LINUX="LINUX"
OS_MAC="MAC"
OS_CYGWIN="CYGWIN"

ARCH_32="i686"
ARCH_64="x86_64"

DISTRO_ADOPTOPENJDK="adoptopenjdk"
DISTRO_OPENJDK="openjdk"
DISTRO_ZULU="zulu"

function error() {
  echo $1
  if [ "$NO_EXIT" == "" ]; then
    exit 1
  fi
}

# Gets the system os and architecture
#
# Sets:
#  OS
#  ARCH
#
function get_system_info() {
  if [ "$OS" != "$OS_LINUX" ] && [ "$OS" != "$OS_MAC" ] && [ "$OS" != "$OS_CYGWIN" ]; then

    readonly ARCH=$(uname -m)

    if [ "$ARCH" != "$ARCH_32" ] && [ "$ARCH" != "$ARCH_64" ]; then
      error "Did not recognize system architecture: $ARCH"
    fi

    if [ "$(uname)" == "Darwin" ]; then
      readonly OS=$OS_MAC
    elif [ "$(uname -s)" == "CYGWIN_NT-5.1" ] || [ "$(uname -s)" == "CYGWIN_NT-6.1" ] || [ "$(uname -s)" == "CYGWIN_NT-6.1-WOW64" ] || [ "$(uname -s)" == "MINGW32_NT-6.2" ]; then
      readonly OS=$OS_CYGWIN
    elif [ "$(uname -o)" == "Cygwin" ]; then
      readonly OS=$OS_CYGWIN
    elif [ "$(uname -o)" == "GNU/Linux" ]; then
      readonly OS=$OS_LINUX
    else
      error "Did not recognize OS: $(uname)"
    fi
  fi
}

# Download the jq tool
#
# Requires:
#  BASE_LOCAL_DIR
#  JQ_VERSION
#  OS
#  OS_LINUX
#  OS_MAC
#  OS_CYGWIN
#  ARCH
#  ARCH_32
#  ARCH_64
#
# Sets:
#  JQ_BIN
#
function download_jq() {

  if [ "$JQ_BIN" == "" ]; then
    readonly JQ_BIN=$BASE_LOCAL_DIR/tools/jq-$JQ_VERSION

    if [ ! -e $JQ_BIN ]; then
      $(mkdir -p $BASE_LOCAL_DIR/tools)

      local JQ_HOST="github.com"

      if [ "$OS" == "$OS_LINUX" ] && [ "$ARCH" == "$ARCH_32" ]; then
        local JQ_PATH="/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-linux-x86"
      elif [ "$OS" == "$OS_LINUX" ] && [ "$ARCH" == "$ARCH_64" ]; then
        local JQ_PATH="/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-linux-x86_64"
      elif [ "$OS" == "$OS_MAC" ] && [ "$ARCH" == "$ARCH_32" ]; then
        local JQ_PATH="/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-osx-x86"
      elif [ "$OS" == "$OS_MAC" ] && [ "$ARCH" == "$ARCH_64" ]; then
        local JQ_PATH="/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-osx-x86_64"
      elif [ "$OS" == "$OS_CYGWIN" ] && [ "$ARCH" == "$ARCH_32" ]; then
        local JQ_PATH="/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-win32.exe"
      elif [ "$OS" == "$OS_CYGWIN" ] && [ "$ARCH" == "$ARCH_64" ]; then
        local JQ_PATH="/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-win64.exe"
      fi

      $(curl -s -L -o $JQ_BIN https://$JQ_HOST$JQ_PATH)

      $(chmod +x $JQ_BIN)
    fi
  fi

}

# Parse the system.properties to find the requested version
#
# Sets:
#  JAVA_RELEASE_NAME
#  JAVA_BINARY_LINK
#
# Supported distros: AdoptOpenJDK
# Coming soon: OpenJDK, Zulu
#
# Defaults to AdoptOpenJDK if no distro is specified
#
function get_version() {
  unset JAVA_RELEASE_NAME
  unset JAVA_BINARY_LINK
  unset VERSION_JSON

  if [ -e system.properties ]; then
    local java_runtime_version=$(cat system.properties | grep "java.runtime.version=" | cut -d'=' -f2)
  elif [ -e pom.xml ]; then
    local java_runtime_version=$(sed -n 's/^.*<java\.version>\(.*\)<\/java\.version>.*$/\1/p' pom.xml)
  fi

  if [ "$java_runtime_version" != "" ]; then

    local maybeDistro=$(echo $java_runtime_version | cut -d'-' -f1)
    local maybeVersion=$(echo $java_runtime_version | cut -d'-' -f2)
    if [ "$maybeDistro" == "$DISTRO_ADOPTOPENJDK" ]; then
      # adoptopenjdk-VERSION
      #
      #  adoptopenjdk-1.8
      #  adoptopenjdk-1.8.0
      #  adoptopenjdk-1.8.0_212

      local java_distro=$DISTRO_ADOPTOPENJDK
      local requested_version=$maybeVersion
    else
      # version
      #
      # 1.8
      # 1.8.0
      # 1.8.0_212
      #
      # 9
      # 9.0
      # 9.0.4

      local java_distro=$DISTRO_ADOPTOPENJDK
      local requested_version=$java_runtime_version
    fi

    # transform requested into an actual version
    if [ "$java_distro" == "$DISTRO_ADOPTOPENJDK" ]; then
      function latest() {
        local major=$1
        local minor=$2
        local patch=$3

        [ $OS == $OS_LINUX ] && local os=linux
        [ $OS == $OS_MAC ] && local os=mac
        [ $OS == $OS_CYGWIN ] && local os=windows

        [ $ARCH == $ARCH_32 ] && local arch=x32
        [ $ARCH == $ARCH_64 ] && local arch=x64

        local latest_url="https://api.adoptopenjdk.net/v2/latestAssets/releases/openjdk$major?os=$os&arch=$arch&openjdk_impl=hotspot&type=jdk"
        local releases_url="https://api.adoptopenjdk.net/v2/info/releases/openjdk$major?os=$os&arch=$arch&openjdk_impl=hotspot&type=jdk"

        if [ "$minor" == "" ] && [ "$patch" == "" ]; then
          local version_json=$(curl -s $latest_url)
          JAVA_RELEASE_NAME=$(echo $version_json | $JQ_BIN -r '.[].release_name')
          JAVA_BINARY_LINK=$(echo $version_json | $JQ_BIN -r '.[].binary_link')
        else
          if [ "$patch" == "" ]; then
            local version_prefix="$major.$minor"
          else
            local version_prefix="$major.$minor.$patch"
          fi

          # find semver latest matching requested version prefix
          local versions_json=$(curl -s $releases_url | $JQ_BIN -r "[.[] | select(.binaries[].version_data.semver | startswith(\"$version_prefix\"))]")

          if [ "$versions_json" != "[]" ]; then
            local semvers=$(echo $versions_json | $JQ_BIN -r ".[].binaries[].version_data.semver")
            local latest_major=0
            local latest_minor=0
            local latest_patch=0
            local latest_tag=0
            for semver in $semvers; do
              local this_major=$(echo $semver | cut -d'.' -f1)
              local this_minor=$(echo $semver | cut -d'.' -f2)
              local this_patch_tag=$(echo $semver | cut -d'.' -f3)
              local this_patch=$(echo $this_patch_tag | cut -d'+' -f1)
              local this_tag=$(echo $this_patch_tag | cut -d'+' -f2)

              if [ "$this_major" -gt "$latest_major" ] ||
                ([ "$this_major" == "$latest_major" ] && [ "$this_minor" -gt "$latest_minor" ]) ||
                ([ "$this_major" == "$latest_major" ] && [ "$this_minor" == "$latest_minor" ] && [ "$this_patch" -gt "$latest_patch" ]) ||
                ([ "$this_major" == "$latest_major" ] && [ "$this_minor" == "$latest_minor" ] && [ "$this_patch" == "$latest_patch" ] && [ "$this_tag" -gt "$latest_tag" ]); then
                  latest_major=$this_major
                  latest_minor=$this_minor
                  latest_patch=$this_patch
                  latest_tag=$this_tag
              fi
            done

            local latest_version="$latest_major.$latest_minor.$latest_patch+$latest_tag"
            local version_json=$(echo $versions_json | $JQ_BIN -r "[.[] | select(.binaries[].version_data.semver == \"$latest_version\")] | .[0]")

            JAVA_RELEASE_NAME=$(echo $version_json | $JQ_BIN -r '.release_name')
            JAVA_BINARY_LINK=$(echo $version_json | $JQ_BIN -r '.binaries[].binary_link')
          fi
        fi
      }

      if [ "${requested_version#1.8}" != "${requested_version}" ]; then
        local major=8

        if [ "${requested_version#1.8.}" != "${requested_version}" ]; then
          local minor_patch=${requested_version#1.8.}
          local minor=$(echo $minor_patch | cut -d'_' -f1)
          if [ "$minor_patch" == "$minor" ]; then
            local patch=""
          else
            local patch=$(echo $minor_patch | cut -d'_' -f2)
          fi
        else
          local minor=""
          local patch=""
        fi
      else
        local major=$(echo $requested_version | cut -d'.' -f1)
        if [ "$requested_version" == "$major" ]; then
          local minor=""
          local patch=""
        else
          local minor=$(echo $requested_version | cut -d'.' -f2)
          if [ "$requested_version" == "$major.$minor" ]; then
            local patch=""
          else
            local patch=$(echo $requested_version | cut -d'.' -f3)
          fi
        fi
      fi

      latest $major $minor $patch

      if [ "$JAVA_RELEASE_NAME" == "" ] || [ "$JAVA_BINARY_LINK" == "" ]; then
        error "Could not find a matching JDK version: $requested_version"
      fi

    fi

  fi
}

# Download the jdk
#
# Requires:
#  BASE_LOCAL_DIR
#  JAVA_RELEASE_NAME
#  JAVA_BINARY_LINK
#
# Sets:
#  JAVA_HOME
#
function download_jdk() {
  if [ "$JAVA_RELEASE_NAME" != "" ] && [ "$JAVA_BINARY_LINK" != "" ]; then
    unset JAVA_HOME

    if [ "$OS" == "$OS_LINUX" ]; then
      JAVA_HOME=$BASE_LOCAL_DIR/$JAVA_RELEASE_NAME
    elif [ "$OS" == "$OS_MAC" ]; then
      JAVA_HOME=$BASE_LOCAL_DIR/$JAVA_RELEASE_NAME/Contents/Home
    else
      error "Could not figure out the JAVA_HOME"
    fi

    if [ ! -e $JAVA_HOME ]; then
      $(mkdir -p $BASE_LOCAL_DIR)

      local oldpwd=$PWD
      cd $BASE_LOCAL_DIR
      echo "Downloading $JAVA_RELEASE_NAME for $ARCH $OS"

      curl -s -L "$JAVA_BINARY_LINK" | tar -zx
      cd $oldpwd
    fi
  fi
}


#
# Main
#

get_system_info

# Mac Special Sauce - Launching a bash script from Finder sets the PWD to the user's home dir
[[ "$OS" == "$OS_MAC" ]] && [[ "$HOME" == "$PWD" ]] && [[ "${#residual_args}" == "0" ]] && {
  cd "$(dirname "$0")"
}

download_jq

# Parse system.properties
get_version

# Download JDK if needed
download_jdk $JAVA_RELEASE_NAME $JAVA_BINARY_LINK

if [ "${PATH#*$JAVA_HOME}" == "$PATH" ]; then
  PATH=$JAVA_HOME/bin:$PATH
fi

### End ejv


SBT_OPTS="-Xms512M -Xmx1536M -Xss1M -XX:+CMSClassUnloadingEnabled"
java $SBT_OPTS -jar `dirname $0`/sbt-launch.jar "$@"

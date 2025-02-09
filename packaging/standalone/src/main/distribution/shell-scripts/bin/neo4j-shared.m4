#############################################################
#      File content is generated based on .m4 template      #
#############################################################

set -o errexit -o nounset -o pipefail
[[ "${TRACE:-}" ]] && set -o xtrace

declare -r PROGRAM="$(basename "$0")"

# Sets up the standard environment for running Neo4j shell scripts.
#
# Provides these environment variables:
#   NEO4J_HOME
#   NEO4J_CONF
#   NEO4J_DATA
#   NEO4J_LIB
#   NEO4J_LOGS
#   NEO4J_PIDFILE
#   NEO4J_PLUGINS
#   one per config setting, with dots converted to underscores
#
setup_environment() {
  _setup_calculated_paths
  _read_config
  _setup_configurable_paths
}

setup_heap() {
  JAVA_MEMORY_OPTS=()
  if [[ -n "${HEAP_SIZE:-}" ]]; then
    JAVA_MEMORY_OPTS+=("-Xmx${HEAP_SIZE}")
  fi
}

build_classpath() {
  CLASSPATH="${NEO4J_PLUGINS}:${NEO4J_CONF}:${NEO4J_LIB}/*:${NEO4J_PLUGINS}/*"

  # augment with tools.jar, will need JDK
  if [ "${JAVA_HOME:-}" ]; then
    JAVA_TOOLS="${JAVA_HOME}/lib/tools.jar"
    if [[ -e $JAVA_TOOLS ]]; then
      CLASSPATH="${CLASSPATH}:${JAVA_TOOLS}"
    fi
  fi
}

detect_os() {
  if uname -s | grep -q Darwin; then
    DIST_OS="macosx"
  elif [[ -e /etc/gentoo-release ]]; then
    DIST_OS="gentoo"
  else
    DIST_OS="other"
  fi
}

setup_memory_opts() {
  if [[ -n "${dbms_memory_heap_initial_size:-}" ]]; then
    local mem="${dbms_memory_heap_initial_size}"
    if ! [[ ${mem} =~ .*[gGmMkK] ]]; then
      mem="${mem}m"
      cat >&2 <<EOF
WARNING: dbms.memory.heap.initial_size will require a unit suffix in a
         future version of Neo4j. Please add a unit suffix to your
         configuration. Example:

         dbms.memory.heap.initial_size=512m
                                          ^
EOF
    fi
    JAVA_MEMORY_OPTS+=("-Xms${mem}")
  fi
  if [[ -n "${dbms_memory_heap_max_size:-}" ]]; then
    local mem="${dbms_memory_heap_max_size}"
    if ! [[ ${mem} =~ .*[gGmMkK] ]]; then
      mem="${mem}m"
      cat >&2 <<EOF
WARNING: dbms.memory.heap.max_size will require a unit suffix in a
         future version of Neo4j. Please add a unit suffix to your
         configuration. Example:

         dbms.memory.heap.max_size=512m
                                      ^
EOF
    fi
    JAVA_MEMORY_OPTS+=("-Xmx${mem}")
  fi
}

check_java() {
  _find_java_cmd
  setup_memory_opts

  version_command=("${JAVA_CMD}" "-version")
  [[ -n "${JAVA_MEMORY_OPTS:-}" ]] && version_command+=("${JAVA_MEMORY_OPTS[@]}")

  JAVA_VERSION=$("${version_command[@]}" 2>&1 | awk -F '"' '/version/ {print $2}')
  if [[ $JAVA_VERSION = "1."* ]] || [[ $JAVA_VERSION = "11"* ]]; then
    if [[ "${JAVA_VERSION}" < "1.8" ]]; then
      echo "ERROR! Neo4j cannot be started using java version ${JAVA_VERSION}. "
      _show_java_help
      exit 1
    fi
    if ! ("${version_command[@]}" 2>&1 | egrep -q "(Java HotSpot\\(TM\\)|OpenJDK|IBM) (64-Bit Server|Server|Client|J9) VM"); then
      unsupported_runtime_warning
    fi
  else
      unsupported_runtime_warning
  fi
}

unsupported_runtime_warning() {
    echo "WARNING! You are using an unsupported Java runtime. "
    _show_java_help
}

# Resolve a path relative to $NEO4J_HOME.  Don't resolve if
# the path is absolute.
resolve_path() {
    orig_filename=$1
    if [[ ${orig_filename} == /* ]]; then
        filename="${orig_filename}"
    else
        filename="${NEO4J_HOME}/${orig_filename}"
    fi
    echo "${filename}"
}

call_main_class() {
  setup_environment
  check_java
  build_classpath
  EXTRA_JVM_ARGUMENTS="-Dfile.encoding=UTF-8"
  class_name=$1
  shift

  export NEO4J_HOME NEO4J_CONF

  exec "${JAVA_CMD}" ${JAVA_OPTS:-} ${JAVA_MEMORY_OPTS[@]:-} \
    -classpath "${CLASSPATH}" \
    ${EXTRA_JVM_ARGUMENTS:-} \
    $class_name "$@"
}

_find_java_cmd() {
  [[ "${JAVA_CMD:-}" ]] && return
  detect_os
  _find_java_home

  if [[ "${JAVA_HOME:-}" ]] ; then
    JAVA_CMD="${JAVA_HOME}/bin/java"
    if [[ ! -f "${JAVA_CMD}" ]]; then
      echo "ERROR: JAVA_HOME is incorrectly defined as ${JAVA_HOME} (the executable ${JAVA_CMD} does not exist)"
      exit 1
    fi
  else
    if [ "${DIST_OS}" != "macosx" ] ; then
      # Don't use default java on Darwin because it displays a misleading dialog box
      JAVA_CMD="$(which java || true)"
    fi
  fi

  if [[ ! "${JAVA_CMD:-}" ]]; then
    echo "ERROR: Unable to find Java executable."
    _show_java_help
    exit 1
  fi
}

_find_java_home() {
  [[ "${JAVA_HOME:-}" ]] && return

  case "${DIST_OS}" in
    "macosx")
      JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
      ;;
    "gentoo")
      JAVA_HOME="$(java-config --jre-home)"
      ;;
  esac
}

_show_java_help() {
  echo "* Please use Oracle(R) Java(TM) 8, OpenJDK(TM) or IBM J9 to run Neo4j."
  echo "* Please see https://neo4j.com/docs/ for Neo4j installation instructions."
}

_setup_calculated_paths() {
  if [[ -z "${NEO4J_HOME:-}" ]]; then
    NEO4J_HOME="$(cd "$(dirname "$0")"/.. && pwd)"
  fi
  : "${NEO4J_CONF:="${NEO4J_HOME}/conf"}"
  readonly NEO4J_HOME NEO4J_CONF
}

_read_config() {
  # - plain key-value pairs become environment variables
  # - keys have '.' chars changed to '_'
  # - keys of the form KEY.# (where # is a number) are concatenated into a single environment variable named KEY
  parse_line() {
    line="$1"
    if [[ "${line}" =~ ^([^#\s][^=]+)=(.+)$ ]]; then
      key="${BASH_REMATCH[1]//./_}"
      value="${BASH_REMATCH[2]}"
      if [[ "${key}" =~ ^(.*)_([0-9]+)$ ]]; then
        key="${BASH_REMATCH[1]}"
      fi
      # Ignore keys that start with a number because export ${key}= will fail - it is not valid for a bash env var to start with a digit
      if [[ ! "${key}" =~ ^[0-9]+.*$ ]]; then
        if [[ "${!key:-}" ]]; then
          export ${key}="${!key} ${value}"
        else
          export ${key}="${value}"
        fi
      else
        echo >&2 "WARNING: Ignoring key ${key}, environment variables cannot start with a number."
      fi
    fi
  }

  if [[ -f "${NEO4J_CONF}/neo4j-wrapper.conf" ]]; then
    cat >&2 <<EOF
WARNING: neo4j-wrapper.conf is deprecated and support for it will be removed in a future
         version of Neo4j; please move all your settings to neo4j.conf
EOF
  fi

  for file in "neo4j-wrapper.conf" "neo4j.conf"; do
    path="${NEO4J_CONF}/${file}"
    if [ -e "${path}" ]; then
      while read line; do
        parse_line "${line}"
      done <"${path}"
    fi
  done
}

_setup_configurable_paths() {
  NEO4J_DATA=$(resolve_path "${dbms_directories_data:-data}")
  NEO4J_LIB=$(resolve_path "${dbms_directories_lib:-lib}")
  NEO4J_LOGS=$(resolve_path "${dbms_directories_logs:-logs}")
  NEO4J_PLUGINS=$(resolve_path "${dbms_directories_plugins:-plugins}")
  NEO4J_RUN=$(resolve_path "${dbms_directories_run:-run}")
  NEO4J_CERTS=$(resolve_path "${dbms_directories_certificates:-certificates}")

  if [ -z "${dbms_directories_import:-}" ]; then
    NEO4J_IMPORT="NOT SET"
  else
    NEO4J_IMPORT=$(resolve_path "${dbms_directories_import:-}")
  fi

  readonly NEO4J_DATA NEO4J_LIB NEO4J_LOGS NEO4J_PLUGINS NEO4J_RUN NEO4J_IMPORT NEO4J_CERTS
}

print_configurable_paths() {
  cat <<EOF
Directories in use:
  home:         ${NEO4J_HOME}
  config:       ${NEO4J_CONF}
  logs:         ${NEO4J_LOGS}
  plugins:      ${NEO4J_PLUGINS}
  import:       ${NEO4J_IMPORT}
  data:         ${NEO4J_DATA}
  certificates: ${NEO4J_CERTS}
  run:          ${NEO4J_RUN}
EOF
}

print_active_database() {
  echo "Active database: ${dbms_active_database:-graph.db}"
}

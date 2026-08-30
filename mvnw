#!/bin/sh
set -eu

MVNW_VERSION=3.9.11
MVNW_SHA512=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978
MVNW_ARCHIVE_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MVNW_VERSION}/apache-maven-${MVNW_VERSION}-bin.tar.gz"
MVNW_USER_HOME="${MAVEN_USER_HOME:-${HOME}/.m2}"
MVNW_INSTALL_DIR="${MVNW_USER_HOME}/wrapper/dists/apache-maven-${MVNW_VERSION}"
MVNW_MAVEN="${MVNW_INSTALL_DIR}/bin/mvn"

if [ ! -x "${MVNW_MAVEN}" ]; then
  command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Maven" >&2; exit 1; }
  command -v tar >/dev/null 2>&1 || { echo "tar is required to bootstrap Maven" >&2; exit 1; }
  MVNW_TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/enjoy-agent-maven.XXXXXX")"
  trap 'rm -rf "${MVNW_TEMP_DIR}"' EXIT HUP INT TERM
  curl --fail --location --silent --show-error "${MVNW_ARCHIVE_URL}" -o "${MVNW_TEMP_DIR}/maven.tar.gz"
  if command -v sha512sum >/dev/null 2>&1; then
    MVNW_ACTUAL_SHA512="$(sha512sum "${MVNW_TEMP_DIR}/maven.tar.gz" | awk '{print $1}')"
  else
    MVNW_ACTUAL_SHA512="$(shasum -a 512 "${MVNW_TEMP_DIR}/maven.tar.gz" | awk '{print $1}')"
  fi
  [ "${MVNW_ACTUAL_SHA512}" = "${MVNW_SHA512}" ] || { echo "Maven archive checksum mismatch" >&2; exit 1; }
  tar -xzf "${MVNW_TEMP_DIR}/maven.tar.gz" -C "${MVNW_TEMP_DIR}"
  mkdir -p "$(dirname "${MVNW_INSTALL_DIR}")"
  if [ ! -d "${MVNW_INSTALL_DIR}" ]; then
    mv "${MVNW_TEMP_DIR}/apache-maven-${MVNW_VERSION}" "${MVNW_INSTALL_DIR}"
  fi
  trap - EXIT HUP INT TERM
  rm -rf "${MVNW_TEMP_DIR}"
fi

exec "${MVNW_MAVEN}" "$@"

#!/usr/bin/env bash

# Copyright 2014 The Kubernetes Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Verify that the right utilities and such are installed for building Kube. Set
# up some dynamic constants.
# Args:
#   $1 - boolean of whether to require functioning docker (default true)
#
# Vars set:
#   KUBE_ROOT_HASH
#   KUBE_BUILD_CONTAINER_NAME_BASE
#   KUBE_BUILD_CONTAINER_NAME
# shellcheck disable=SC2120 # optional parameters
function kube::build::verify_prereqs() {
  local -r require_docker=${1:-true}
  kube::log::status "Verifying Prerequisites...."
  kube::build::ensure_tar || return 1
  if ${require_docker}; then
    kube::build::ensure_docker_in_path || return 1
    if kube::build::is_osx; then
        kube::build::docker_available_on_osx || return 1
    fi
    kube::util::ensure_docker_daemon_connectivity || return 1

    if (( KUBE_VERBOSE > 6 )); then
      kube::log::status "Docker Version:"
      "${DOCKER[@]}" version | kube::log::info_from_stdin
    fi
  fi

  kube::build::setup_vars

  kube::version::get_version_vars
  kube::version::save_version_vars "${KUBE_ROOT}/.dockerized-kube-version-defs"

  # Without this, the user's umask can leak through.
  umask 0022
}

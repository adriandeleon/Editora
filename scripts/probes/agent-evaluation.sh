#!/usr/bin/env bash
# Live, opt-in coding evaluation. Requires the same JDK/Maven setup as Editora.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
: "${AGENT_EVAL_MODEL:?Set AGENT_EVAL_MODEL to an installed/configured tool-capable model}"
exec mvn test -Dtest=AgentCodingEvaluationTest -Dagent.eval=true \
  "-Dagent.eval.model=$AGENT_EVAL_MODEL" "$@"

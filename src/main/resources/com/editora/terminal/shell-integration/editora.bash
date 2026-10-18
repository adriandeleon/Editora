# Editora's shell integration for bash. Started as `bash --init-file <this> -i` instead of a login shell, so it
# first reads what a login shell reads, then makes the prompt tell the terminal where the shell is (OSC 7) and
# where each prompt, command output and exit status are (OSC 133 A, C, D). Your own files are not changed.
if [ -z "${__EDITORA_INTEGRATION:-}" ]; then
    __EDITORA_INTEGRATION=1
    [ -r /etc/profile ] && . /etc/profile
    if [ -r "$HOME/.bash_profile" ]; then
        . "$HOME/.bash_profile"
    elif [ -r "$HOME/.bash_login" ]; then
        . "$HOME/.bash_login"
    elif [ -r "$HOME/.profile" ]; then
        . "$HOME/.profile"
    fi

    # A command ran since the last prompt when the history number moved (an empty line does not move it).
    # HISTCMD is that number in bash 4+; bash 3.2 (macOS's /bin/bash) reports 1 there whatever ran, so it asks
    # the history itself.
    if [ "${BASH_VERSINFO[0]:-0}" -ge 4 ]; then
        __editora_histno() { __editora_n=${HISTCMD:-}; }
    else
        __editora_histno() {
            local h
            h=$(builtin history 1)
            h=${h#"${h%%[! ]*}"}
            __editora_n=${h%% *}
        }
    fi
    __editora_histno
    __editora_last=${__editora_n:-0}
    __editora_before() {
        local status=$?
        __editora_histno
        if [ -n "${__editora_n}" ] && [ "$__editora_n" != "$__editora_last" ]; then
            printf '\033]133;D;%s\007' "$status"
        fi
        __editora_last=${__editora_n:-$__editora_last}
        local p=${PWD//%/%25}
        printf '\033]7;file://%s%s\007' "${HOSTNAME:-localhost}" "${p// /%20}"
        printf '\033]133;A\007'
        return $status
    }
    # PS0 is printed after a command is read and before it runs (bash 4.4+; older ones have no output mark): the
    # output mark. A literal escape,
    # not a function call — PS0's command substitutions run in a subshell.
    PS0=$'\033]133;C\007'"${PS0:-}"
    PROMPT_COMMAND="__editora_before${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
fi

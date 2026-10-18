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

    # What a login shell does that --init-file cannot ask for: `logout` works, and ~/.bash_logout runs at exit
    # (it often ends an ssh-agent or clears the screen).
    logout() { exit "$@"; }
    [ -z "$(trap -p EXIT)" ] && trap '[ -r "$HOME/.bash_logout" ] && . "$HOME/.bash_logout"' EXIT

    # A command ran since the last prompt when the command number (\#) moved: an empty line does not move it,
    # and unlike the history number neither does HISTCONTROL=ignoreboth dropping a repeat. ${…@P} is bash 4.4+;
    # older ones fall back to the history number (HISTCMD in bash 4; bash 3.2, macOS's /bin/bash, reports 1 there
    # whatever ran, so it asks the history itself).
    if [ "${BASH_VERSINFO[0]:-0}" -ge 5 ] || { [ "${BASH_VERSINFO[0]:-0}" -eq 4 ] && [ "${BASH_VERSINFO[1]:-0}" -ge 4 ]; }; then
        __editora_cmdno='\#'
        __editora_histno() { __editora_n=${__editora_cmdno@P}; }
    elif [ "${BASH_VERSINFO[0]:-0}" -ge 4 ]; then
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

    # The folder as a file: URL: every byte outside the unreserved set percent-encoded, so a name with # ? [ "
    # | { \ or a space survives the trip.
    __editora_url() {
        local LC_ALL=C s=$1 c i
        __editora_u=
        for (( i = 0; i < ${#s}; i++ )); do
            c=${s:i:1}
            case $c in
                [a-zA-Z0-9/._~-]) __editora_u+=$c ;;
                *) printf -v c '%%%02X' "'$c"; __editora_u+=$c ;;
            esac
        done
    }
    __editora_before() {
        local status=$?
        __editora_histno
        if [ -n "${__editora_n}" ] && [ "$__editora_n" != "$__editora_last" ]; then
            printf '\033]133;D;%s\007' "$status"
        fi
        __editora_last=${__editora_n:-$__editora_last}
        __editora_url "$PWD"
        printf '\033]7;file://%s%s\007' "${HOSTNAME:-localhost}" "$__editora_u"
        printf '\033]133;A\007'
        return $status
    }
    # PS0 is printed after a command is read and before it runs (bash 4.4+; older ones have no output mark): the
    # output mark. A literal escape, not a function call — PS0's command substitutions run in a subshell.
    PS0=$'\033]133;C\007'"${PS0:-}"
    PROMPT_COMMAND="__editora_before${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
fi

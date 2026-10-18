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
    __editora_last=${HISTCMD:-}
    __editora_before() {
        local status=$?
        if [ -n "${__editora_last}" ] && [ "${HISTCMD:-}" != "$__editora_last" ]; then
            printf '\033]133;D;%s\007' "$status"
        fi
        __editora_last=${HISTCMD:-}
        local p=${PWD//%/%25}
        printf '\033]7;file://%s%s\007' "${HOSTNAME:-localhost}" "${p// /%20}"
        printf '\033]133;A\007'
        return $status
    }
    # PS0 is printed after a command is read and before it runs (bash 4.4+): the output mark. A literal escape,
    # not a function call — PS0's command substitutions run in a subshell.
    PS0=$'\033]133;C\007'"${PS0:-}"
    PROMPT_COMMAND="__editora_before${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
fi

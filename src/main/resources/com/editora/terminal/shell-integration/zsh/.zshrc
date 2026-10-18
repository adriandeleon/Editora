__editora_zdotdir=$ZDOTDIR
ZDOTDIR=$USER_ZDOTDIR
[[ -r "$ZDOTDIR/.zshrc" ]] && source "$ZDOTDIR/.zshrc"
USER_ZDOTDIR=$ZDOTDIR
ZDOTDIR=$__editora_zdotdir

# Where the shell is (OSC 7) and where each prompt, command output and exit status are (OSC 133 A, C, D).
typeset -g __editora_ran=0
__editora_precmd() {
    local ret=$?  # not "status": zsh's $status is $?, read-only
    (( __editora_ran )) && printf '\033]133;D;%s\007' "$ret"
    __editora_ran=0
    local p=${PWD//\%/%25}
    printf '\033]7;file://%s%s\007' "${HOST:-localhost}" "${p// /%20}"
    printf '\033]133;A\007'
}
__editora_preexec() {
    __editora_ran=1
    printf '\033]133;C\007'
}
autoload -Uz add-zsh-hook
add-zsh-hook precmd __editora_precmd
add-zsh-hook preexec __editora_preexec

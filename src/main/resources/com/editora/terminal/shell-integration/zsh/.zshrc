__editora_zdotdir=$ZDOTDIR
ZDOTDIR=$USER_ZDOTDIR
[[ -r "$ZDOTDIR/.zshrc" ]] && source "$ZDOTDIR/.zshrc"
USER_ZDOTDIR=$ZDOTDIR
ZDOTDIR=$__editora_zdotdir

# Where the shell is (OSC 7) and where each prompt, command output and exit status are (OSC 133 A, C, D).

# The folder as a file: URL path in $REPLY: every byte outside the unreserved set percent-encoded, so a name with
# # ? [ " | { \ or a space survives the trip.
__editora_url() {
    emulate -L zsh
    setopt no_multibyte
    local s=$1 c
    local -i i
    REPLY=
    for (( i = 1; i <= ${#s}; i++ )); do
        c=$s[i]
        if [[ $c == [a-zA-Z0-9/._~-] ]]; then
            REPLY+=$c
        else
            REPLY+=%${(l:2::0:)$(( [##16] #c ))}
        fi
    done
}
typeset -g __editora_ran=0
__editora_precmd() {
    local ret=$?  # not "status": zsh's $status is $?, read-only
    (( __editora_ran )) && printf '\033]133;D;%s\007' "$ret"
    __editora_ran=0
    __editora_url "$PWD"
    printf '\033]7;file://%s%s\007' "${HOST:-localhost}" "$REPLY"
    printf '\033]133;A\007'
}
__editora_preexec() {
    __editora_ran=1
    printf '\033]133;C\007'
}
autoload -Uz add-zsh-hook
add-zsh-hook precmd __editora_precmd
add-zsh-hook preexec __editora_preexec

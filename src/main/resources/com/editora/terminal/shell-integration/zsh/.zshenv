# Editora's shell integration for zsh: ZDOTDIR points here so this folder's files run; each runs yours first.
__editora_zdotdir=$ZDOTDIR
ZDOTDIR=${USER_ZDOTDIR:-$HOME}
[[ -r "$ZDOTDIR/.zshenv" ]] && source "$ZDOTDIR/.zshenv"
USER_ZDOTDIR=$ZDOTDIR
ZDOTDIR=$__editora_zdotdir

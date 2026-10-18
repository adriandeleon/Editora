__editora_zdotdir=$ZDOTDIR
ZDOTDIR=$USER_ZDOTDIR
[[ -r "$ZDOTDIR/.zprofile" ]] && source "$ZDOTDIR/.zprofile"
USER_ZDOTDIR=$ZDOTDIR
ZDOTDIR=$__editora_zdotdir

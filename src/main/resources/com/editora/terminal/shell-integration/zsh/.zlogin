ZDOTDIR=$USER_ZDOTDIR
[[ -r "$ZDOTDIR/.zlogin" ]] && source "$ZDOTDIR/.zlogin"
# From here on the shell's own ZDOTDIR is yours again, so a zsh started inside it reads your files only.

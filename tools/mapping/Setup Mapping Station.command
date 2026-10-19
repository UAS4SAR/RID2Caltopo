#!/bin/bash
cd "$(dirname "$0")"
/bin/bash ./setup-mac.sh
mapping_exit=$?
printf '\n%s\n' 'Press Return to close this window.'
read -r
exit "$mapping_exit"

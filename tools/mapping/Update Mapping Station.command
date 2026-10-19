#!/bin/bash
cd "$(dirname "$0")"
/bin/bash ./update-mac.sh
mapping_exit=$?
printf '\n%s\n' 'Press Return to close this window. The updated station keeps running.'
read -r
exit "$mapping_exit"

#!/bin/sh
set -eu
umask 077

if [ "$#" -ne 2 ]; then
    echo "Usage: sh ops/backup.sh DOCUMENTS_DIRECTORY BACKUP_DIRECTORY" >&2
    exit 2
fi

documents=$(realpath -e -- "$1")
if [ ! -d "$documents" ]; then
    echo "The documents path must be a directory." >&2
    exit 2
fi
destination=$(realpath -m -- "$2")
case "$destination/" in
    "${documents%/}/"*)
        echo "Use a backup directory outside the documents directory." >&2
        exit 2
        ;;
esac
mkdir -p -- "$destination"

staging=$(mktemp -d "$destination/.partial-XXXXXXXX")
cleanup() {
    rm -f -- "$staging/database.dump" "$staging/documents.tar.gz"
    rmdir -- "$staging"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

pg_dump --no-password --format=custom --file="$staging/database.dump"
pg_restore --list "$staging/database.dump" >/dev/null
tar --force-local -C "$documents" -czf "$staging/documents.tar.gz" .
tar --force-local -tzf "$staging/documents.tar.gz" >/dev/null

final="$destination/assistant-$(date -u +%Y%m%dT%H%M%SZ)-${staging##*.partial-}"
mv -T -- "$staging" "$final"
trap - EXIT
printf '%s\n' "$final"

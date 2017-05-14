@echo off

set "source=%~1"
set "target=%~2"

:: explicit output format "-f mp3" is required as the conversion target will be a temp file.
ffmpeg -y -i "%source%" %FFMPEG_OPTIONS% -q:a 5 -map_metadata 0 -id3v2_version 3 -f mp3 "%target%"

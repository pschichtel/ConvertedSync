@echo off

set "source=%~1"
set "target=%~2"

:: explicit output format like "-f mp3" is required as the conversion target will be a temp file.
ffmpeg -y -i "%source%" %FFMPEG_OPTIONS% "%target%"

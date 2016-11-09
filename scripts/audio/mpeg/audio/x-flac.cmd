
set "source=%~1"
set "target=%~2"

if "%MP3_BITRATE%"=="" (
    set MP3_BITRATE=248
)

:: explicit output format "-f mp3" is required as the conversion target will be a temp file.
ffmpeg -y -i "%source%" -ab "%MP3_BITRATE%k" -map_metadata 0 -id3v2_version 3 -f mp3 "%target%"

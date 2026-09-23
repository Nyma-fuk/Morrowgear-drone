param(
    [string]$OutputDirectory = "docs/design/operation-voice-v1"
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($output) | Out-Null
$lines = @("迎撃を開始。", "補給のため帰還。", "補給完了。任務を再開。")
$voices = @("Microsoft Haruka Desktop", "Microsoft Ichiro")
foreach ($voice in $voices) {
    $synthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        if (-not ($synthesizer.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Name -eq $voice })) { continue }
        $synthesizer.SelectVoice($voice)
        $synthesizer.Rate = 0
        $synthesizer.Volume = 70
        $name = if ($voice -like '*Ichiro*') { 'voice-low' } else { 'voice-standard' }
        $synthesizer.SetOutputToWaveFile((Join-Path $output "$name.wav"))
        $prompt = New-Object System.Speech.Synthesis.PromptBuilder
        foreach ($line in $lines) {
            $prompt.AppendText($line)
            $prompt.AppendBreak([TimeSpan]::FromMilliseconds(700))
        }
        $synthesizer.Speak($prompt)
        Write-Output "$name : $voice"
    } finally {
        $synthesizer.Dispose()
    }
}

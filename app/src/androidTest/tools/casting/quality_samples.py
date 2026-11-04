"""Generate distinct 360p/720p H.264 + AAC samples for real receiver quality checks."""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)

    def run(*options):
        subprocess.run([args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', *options], check=True)

    for height, width, color in [(360, 640, '0x203570'), (720, 1280, '0x206030')]:
        muxed = root / f'muxed-{height}.mp4'
        run('-f', 'lavfi', '-i', f'color=c={color}:s={width}x{height}:r=24',
            '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '240',
            '-vf', f"drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='{height}p':fontsize={height//5}:fontcolor=white:x=(w-tw)/2:y=h/3,"
                   f"drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='%{{pts\\:hms}}':fontsize={height//11}:fontcolor=white:x=(w-tw)/2:y=h*0.65",
            '-c:v', 'libx264', '-threads', '2', '-preset', 'ultrafast', '-profile:v', 'baseline',
            '-pix_fmt', 'yuv420p', '-g', '48', '-crf', '28',
            '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart', str(muxed))
        run('-i', str(muxed), '-an', '-c:v', 'copy', '-movflags', '+dash+global_sidx',
            '-frag_duration', '2000000', str(root / f'video-{height}.mp4'))
    run('-i', str(root / 'muxed-360.mp4'), '-vn', '-c:a', 'copy', '-movflags', '+dash+global_sidx',
        '-frag_duration', '2000000', str(root / 'audio.mp4'))
    run('-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=48000', '-t', '240', '-c:a', 'aac',
        '-b:a', '96k', '-movflags', '+dash+global_sidx', '-frag_duration', '2000000', str(root / 'audio-es.mp4'))
    print(f'Generated 240-second 360p/720p samples in {root}')


if __name__ == '__main__':
    main()

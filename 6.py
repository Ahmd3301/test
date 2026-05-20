import os
import pty
import subprocess
import re
import sys
import psutil
import time

def format_progress(line, process_pid):
    """
    تنسيق الإخراج في سطر واحد فقط مع عرض استهلاك CPU وRAM.
    """
    match = re.search(r'Uploading "([^"]+)"\s+\[.*\]\s+(\d+)%', line)

    if match:
        filename = match.group(1)
        percentage = int(match.group(2))

        # شريط التقدم
        filled_units = percentage // 5
        progress_bar = '█' * filled_units + '░' * (20 - filled_units)

        # مراقبة العملية
        try:
            proc = psutil.Process(process_pid)
            cpu_usage = proc.cpu_percent(interval=0.1)
            mem_usage = proc.memory_percent()
        except psutil.NoSuchProcess:
            cpu_usage = 0.0
            mem_usage = 0.0

        # سطر الإخراج (سطر واحد)
        output_line = (
            f'\rUploading "{filename}" [{progress_bar}] {percentage}% | '
            f'🧠 CPU {cpu_usage:.1f}% | 🐏 RAM {mem_usage:.1f}%'
        )

        # طباعة مباشرة في نفس السطر
        sys.stdout.write(output_line)
        sys.stdout.flush()
    else:
        # أي سطور أخرى (مثل التحضير أو الخطأ)
        sys.stdout.write('\r' + line.strip() + '\033[K')
        sys.stdout.flush()


# إعداد البيئة
master, slave = pty.openpty()

if not os.path.exists("video.mp4"):
    with open("video.mp4", "wb") as f:
        f.write(os.urandom(5_000_000))  # ملف تجريبي

# تشغيل telegram-upload
process = subprocess.Popen(
    ["telegram-upload", "--to", "me", "video.mp4"],
    stdin=subprocess.PIPE,
    stdout=slave,
    stderr=subprocess.STDOUT,
    text=True,
)

os.close(slave)

# مراقبة التقدم
while True:
    try:
        output = os.read(master, 1024).decode(errors="ignore")
        if not output:
            break
        format_progress(output, process.pid)
    except OSError:
        break

process.wait()

# بعد الانتهاء اطبع سطر جديد
sys.stdout.write('\n✅ Upload complete\n')
sys.stdout.flush()


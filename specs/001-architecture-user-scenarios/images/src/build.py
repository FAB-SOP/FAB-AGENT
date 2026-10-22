"""그림 원본(service.html · tech.html)에 아이콘 · engine.js를 넣어 images/에 HTML과 PNG를 만든다.
사용: python3 specs/001-architecture-user-scenarios/images/src/build.py  (macOS Chrome 필요)"""
import base64, re, subprocess
from pathlib import Path

SRC = Path(__file__).resolve().parent
OUT = SRC.parent
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
engine = (SRC / "engine.js").read_text(encoding="utf-8")

for src, name in [("service.html", "service-architecture"), ("tech.html", "tech-architecture")]:
    t = (SRC / src).read_text(encoding="utf-8").replace("{{engine}}", engine)
    for n in set(re.findall(r"\{\{(\w+)\}\}", t)):
        b = base64.b64encode((SRC / "icons" / f"{n}.svg").read_bytes()).decode()
        t = t.replace("{{" + n + "}}", f"'data:image/svg+xml;base64,{b}'")
    html = OUT / f"{name}.html"
    html.write_text(t, encoding="utf-8")
    dom = subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--window-size=1600,1200", "--dump-dom", html.as_uri()],
                         capture_output=True, text=True).stdout
    h = int(re.search(r'data-h="(\d+)"', dom).group(1))
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2",
                    f"--window-size=1600,{h}", f"--screenshot={OUT / (name + '.png')}", html.as_uri()], capture_output=True)
    print(name, "height", h)

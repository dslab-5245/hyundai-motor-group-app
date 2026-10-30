"""Cross-checks that must hold before a Pleos App Market submission (run: python -I scripts/check_consistency.py).

- Gleo schema (assets/schema.json): function names == manifest intent actions (activity + service) == service GLEO_* constants,
  packageName == applicationId, genre enum == engine Genre names.
- README's engine test count == number of @Test functions in engine/src/test.
- Manifest Pleos permissions == the ones the signal sources actually need (documented set).
- Blind review: no e-mail address other than the project mailbox in tracked text files; every commit author is the
  project pseudonym (the contest forbids developer names in the application form, attachments and GitHub).
Exit code 1 on any mismatch; prints every check either way.
"""
import io, json, os, re, subprocess, sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")


def read(*p):
    return io.open(os.path.join(ROOT, *p), encoding="utf-8").read()


fails = 0


def check(name, ok, detail=""):
    global fails
    print(("OK   " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""))
    if not ok:
        fails += 1


schema = json.loads(read("app", "src", "main", "assets", "schema.json"))
functions = [c["function"]["name"] for c in schema["actionCalls"]]
manifest = read("app", "src", "main", "AndroidManifest.xml")
service_src = read("app", "src", "main", "kotlin", "io", "drivesymphony", "app", "audio", "SymphonyService.kt")
gradle = read("app", "build.gradle.kts")

# 1. schema functions vs manifest actions (both the activity and the service filter)
actions = re.findall(r'<action android:name="([A-Za-z]+)" />', manifest)
for fn in functions:
    check(f"manifest declares action '{fn}' twice (activity + service)", actions.count(fn) == 2, f"found {actions.count(fn)}")

# 2. schema functions vs service constants
consts = dict(re.findall(r'const val (GLEO_[A-Z_]+) = "([^"]+)"', service_src))
gleo_names = {v for k, v in consts.items() if k not in ("GLEO_PARAM_EXTRA", "GLEO_CALLBACK")}
check("service GLEO_* constants == schema functions", gleo_names == set(functions), f"{sorted(gleo_names)} vs {sorted(functions)}")
check("service reads the ACTION_PARAM extra", consts.get("GLEO_PARAM_EXTRA") == "ACTION_PARAM")
check("every schema function is dispatched in onStartCommand",
      all(re.search(rf"\b{k}\b", service_src.split("override fun onStartCommand", 1)[1][:2000]) for k, v in consts.items() if v in functions))

# 3. package name
app_id = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle).group(1)
check("schema packageName == applicationId", schema["packageName"] == app_id, f"{schema['packageName']} vs {app_id}")
check("schema component is 'service' for every function", all(c["function"].get("component") == "service" for c in schema["actionCalls"]))

# 4. genre enum vs engine
theory_dir = os.path.join(ROOT, "engine", "src", "main", "kotlin", "io", "drivesymphony", "engine", "theory")
genre_src = "".join(io.open(os.path.join(theory_dir, f), encoding="utf-8").read() for f in os.listdir(theory_dir))
m = re.search(r"enum class Genre\s*\((?:[^)]*)\)\s*\{(.*?)\}", genre_src, re.S) or re.search(r"enum class Genre\s*\{(.*?)\}", genre_src, re.S)
engine_genres = re.findall(r"^\s*([A-Z]+)\s*\(", m.group(1), re.M) if m else []
schema_genres = set()
for c in schema["actionCalls"]:
    g = c["function"]["parameters"]["properties"].get("genre")
    if g:
        schema_genres.update(g["enum"])
check("schema genre enum == engine Genre", schema_genres == set(engine_genres), f"{sorted(schema_genres)} vs {sorted(engine_genres)}")

# 5. README test count vs @Test functions
test_dir = os.path.join(ROOT, "engine", "src", "test", "kotlin", "io", "drivesymphony", "engine")
n_tests = sum(io.open(os.path.join(test_dir, f), encoding="utf-8").read().count("@Test") for f in os.listdir(test_dir) if f.endswith(".kt"))
readme = read("README.md")
rm = re.search(r"엔진 단위 테스트 \((\d+)개\)", readme)
check("README test count == @Test count", rm is not None and int(rm.group(1)) == n_tests, f"README {rm.group(1) if rm else '?'} vs {n_tests}")

# 6. permissions: exactly the documented set
perms = set(re.findall(r'<uses-permission android:name="([^"]+)" />', manifest))
expected = {
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "android.permission.POST_NOTIFICATIONS",
    "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_LIGHTS", "pleos.car.permission.CAR_WINDOWS", "pleos.car.permission.CAR_SEATS",
    "pleos.car.permission.NAVI_ROUTE", "android.car.permission.CAR_POWERTRAIN",
}
check("manifest permissions == documented set (no INTERNET, no storage)", perms == expected, f"extra {sorted(perms - expected)} missing {sorted(expected - perms)}")
check("no INTERNET permission (privacy policy promise)", "android.permission.INTERNET" not in perms)

# 7. privacy policy mentions the same promise
privacy = read("docs", "privacy-policy.md")
check("privacy policy states no network transmission", "INTERNET" in privacy)

# 8. blind review: e-mail addresses in tracked text files
PROJECT_MAIL = "dev@drivesymphony.local"
mail_re = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
offenders = []
try:
    tracked = subprocess.run(["git", "-C", ROOT, "ls-files"], capture_output=True, text=True, check=True).stdout.split()
except Exception:
    tracked = []
    for base, _, files in os.walk(ROOT):
        if any(x in base for x in (".git", "build", ".gradle", ".kotlin", ".idea")):
            continue
        tracked += [os.path.relpath(os.path.join(base, f), ROOT) for f in files]
for rel in tracked:
    if not rel.endswith((".kt", ".kts", ".md", ".xml", ".json", ".py", ".ps1", ".txt", ".properties", ".toml")):
        continue
    try:
        txt = io.open(os.path.join(ROOT, rel), encoding="utf-8").read()
    except Exception:
        continue
    for m_ in mail_re.findall(txt):
        if m_ != PROJECT_MAIL and not m_.endswith("@anthropic.com"):
            offenders.append(f"{rel}: {m_}")
check("no personal e-mail address in tracked files (blind review)", not offenders, "; ".join(offenders[:5]))

# 9. blind review: commit authors
try:
    authors = sorted(set(subprocess.run(["git", "-C", ROOT, "log", "--format=%an <%ae>", "--all"], capture_output=True, text=True, check=True).stdout.split("\n")) - {""})
    check("every commit author is the project pseudonym", authors == [f"Drive Symphony <{PROJECT_MAIL}>"], "; ".join(authors))
except Exception as e:  # not a git checkout (e.g. the OneDrive mirror)
    print(f"SKIP commit author check ({e.__class__.__name__})")

print(f"\n{'ALL CHECKS PASSED' if fails == 0 else str(fails) + ' CHECK(S) FAILED'}")
sys.exit(1 if fails else 0)

import json, urllib.request, urllib.error

tok = open(r"/tmp/gh_token.txt", encoding="utf-8").read().strip()

url = "https://api.github.com/repos/qianeric-backup/reasonix-proot-app/releases"
body = json.dumps({
    "tag_name": "v1.13",
    "target_commitish": "main",
    "name": "v1.13",
    "body": "Reasonix Proot v1.13\n"
            "- 侧滑栏整合 DS2API 网关（应用内嵌 WebView 打开 http://127.0.0.1:5001/admin/ 管理页，无需额外安装）\n"
            "- 「更新 Reasonix」菜单重命名为「Reasonix 更新」\n"
            "- 更新面板标题同步改为「Reasonix 更新」\n"
            "- 版本递增 versionCode 13 / versionName 1.13",
    "draft": False,
    "prerelease": False,
}).encode()
req = urllib.request.Request(url, data=body, method="POST")
req.add_header("Authorization", "token " + tok)
req.add_header("Accept", "application/vnd.github+json")
req.add_header("Content-Type", "application/json")
try:
    with urllib.request.urlopen(req) as r:
        d = json.load(r)
        print("release_id:", d.get("id"))
        print("tag:", d.get("tag_name"))
        print("html:", d.get("html_url"))
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode()[:400])
const http = require("node:http");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const playwrightModule = process.env.MORROWGEAR_PLAYWRIGHT || "playwright";
const { chromium } = require(playwrightModule);
const browserCandidates = [
  process.env.MORROWGEAR_BROWSER,
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
].filter(Boolean);
const browserExecutable = browserCandidates.find(candidate => fs.existsSync(candidate));

const root = __dirname;
const outputDir = path.resolve(root, "../../docs/design/drone-family-a/validated");
const contentTypes = { ".html": "text/html", ".mjs": "text/javascript", ".js": "text/javascript", ".json": "application/json" };

const server = http.createServer((request, response) => {
  const pathname = new URL(request.url, "http://127.0.0.1").pathname;
  const requested = pathname === "/" ? "/viewer.html" : pathname;
  const file = path.resolve(root, `.${requested}`);
  if (!file.startsWith(root)) {
    response.writeHead(403).end();
    return;
  }
  fs.readFile(file, (error, data) => {
    if (error) {
      response.writeHead(404).end();
      return;
    }
    response.setHeader("Content-Type", contentTypes[path.extname(file)] || "application/octet-stream");
    response.end(data);
  });
});

(async () => {
  await fsp.mkdir(outputDir, { recursive: true });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  const browser = await chromium.launch({ headless: true, executablePath: browserExecutable });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1180 }, deviceScaleFactor: 1 });
  const roles = ["scout", "engineer", "field", "guard"];
  const renderResults = [];
  for (const role of roles) {
    await page.goto(`http://127.0.0.1:${port}/viewer.html?role=${role}`, { waitUntil: "networkidle" });
    await page.waitForFunction(() => window.renderReady === true);
    const summary = await page.evaluate(() => window.renderSummary);
    const screenshot = path.join(outputDir, `family-a-${role}-validated.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    const symmetryViews = summary.stats.filter(item => ["top", "front", "rear"].includes(item.id));
    const pass = summary.stats.every(item => item.occupiedPixels > 1000)
      && symmetryViews.every(item => item.mirrorMismatchRatio < 0.02)
      && summary.sideMirrorMismatchRatio < 0.02;
    renderResults.push({ role, pass, screenshot, ...summary });
    console.log(`${role}: ${pass ? "PASS" : "FAIL"} ${symmetryViews.map(item => `${item.id}=${(item.mirrorMismatchRatio * 100).toFixed(2)}%`).join(" ")} sides=${(summary.sideMirrorMismatchRatio * 100).toFixed(2)}%`);
  }
  await fsp.writeFile(path.join(outputDir, "render-validation.json"), `${JSON.stringify({ pass: renderResults.every(item => item.pass), results: renderResults }, null, 2)}\n`);
  await browser.close();
  server.close();
  if (!renderResults.every(item => item.pass)) process.exitCode = 1;
})().catch(error => {
  console.error(error);
  server.close();
  process.exitCode = 1;
});

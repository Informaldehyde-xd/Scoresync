const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');
const { parseMidi } = require('midi-file');

function parseMidiOnsets(midiPath) {
  const data = fs.readFileSync(midiPath);
  const parsed = parseMidi(data);
  const division = parsed.header.ticksPerBeat || 480;
  let tempoUsPerQuarter = 500000;
  const onsetTicks = new Set();

  for (const track of parsed.tracks) {
    let tick = 0;
    for (const ev of track) {
      tick += ev.deltaTime;
      if (ev.type === 'setTempo') tempoUsPerQuarter = ev.microsecondsPerBeat;
      if (ev.type === 'noteOn' && ev.velocity > 0) onsetTicks.add(tick);
    }
  }

  const msPerTick = tempoUsPerQuarter / 1000 / division;
  return Array.from(onsetTicks).sort((a, b) => a - b).map(t => Math.round(t * msPerTick));
}

async function main() {
  const [, , musicXmlPath, midiPath, configPath, outDir] = process.argv;

  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  const offsetMs = config.offsetMs || 0;
  const fps = config.fps || 24;

  const musicXml = fs.readFileSync(musicXmlPath, 'utf8');
  const onsets = parseMidiOnsets(midiPath);
  const totalDurationMs = onsets.length ? onsets[onsets.length - 1] + 2000 : 5000;
  const totalFrames = Math.ceil((totalDurationMs / 1000) * fps);

  console.log(`Rendering ${totalFrames} frames at ${fps}fps (${onsets.length} note onsets)`);

  const browser = await puppeteer.launch({
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
    defaultViewport: { width: 1920, height: 1080 }
  });
  const page = await browser.newPage();
  await page.goto('file://' + path.resolve(__dirname, 'export.html'));

  await page.evaluate((xml) => window.loadScoreForExport(xml), musicXml);
  await page.waitForFunction(() => window.osmdReady === true, { timeout: 30000 });

  const framesDir = path.join(outDir, 'frames');
  fs.mkdirSync(framesDir, { recursive: true });

  let cursorIndex = 0;
  for (let frame = 0; frame < totalFrames; frame++) {
    const t = (frame / fps) * 1000;
    const targetIndex = onsets.filter(o => o <= (t - offsetMs)).length;

    while (cursorIndex < targetIndex) {
      await page.evaluate(() => window.cursorNextForExport());
      cursorIndex++;
    }
    await page.evaluate(() => window.scrollToCursorForExport());

    const framePath = path.join(framesDir, `frame_${String(frame).padStart(6, '0')}.png`);
    await page.screenshot({ path: framePath });

    if (frame % 100 === 0) console.log(`Frame ${frame}/${totalFrames}`);
  }

  await browser.close();
  console.log('Done rendering frames');
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});

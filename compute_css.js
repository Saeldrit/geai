// Compute CSS values for static rendering
const PFADE = { BASE: 87, MAX: 348 };
const css2top = v => (v / 65535) * PFADE.MAX + PFADE.BASE;
const CSSPercent = p => p.toFixed(2) + '%';
const CSSAngle = a => a.toFixed(4) + 'deg';
const CSSColorPercent = p => (p * 100).toFixed(4) + '%';
const ramp_colors = [
  [0.000, [0.820, 0.125, 0.004]],
  [0.250, [0.914, 0.569, 0.165]],
  [0.500, [0.969, 0.976, 0.671]],
  [0.750, [0.455, 0.788, 0.463]],
  [1.000, [0.302, 0.620, 0.973]],
];

const band_color = fraction => {
  if (fraction <= ramp_colors[0][0]) return ramp_colors[0][1];
  for (let i = 0; i < ramp_colors.length - 1; ++i) {
    const [a1, c1] = [ramp_colors[i][0], ramp_colors[i][1]];
    const [a2, c2] = [ramp_colors[i + 1][0], ramp_colors[i + 1][1]];
    if (fraction < a2) {
      const t = (fraction - a1) / (a2 - a1);
      return [c1[0] + (c2[0] - c1[0]) * t, c1[1] + (c2[1] - c1[1]) * t, c1[2] + (c2[2] - c1[2]) * t];
    }
  }
  return ramp_colors[ramp_colors.length - 1][1];
};

const preview_offset = 200;
const competition_offset = 200;
const fire_pid_offset = 0;
const fire_predict_offset = 200;
const sk_pid2_offset = 200;
const ADC_MAX_SK = 65535;
const ADC_MAX_COMP = 1254;

console.log("=== SK STORED GROUPS (ADC_MAX=65535) ===");
const sk_groups = [
  [0, 493], [494, 2919], [2920, 5602], [5603, 15149], [15150, 17105],
  [17106, 25217], [25218, 32767], [32768, 38353], [38354, 47349], [47350, 47490],
  [47491, 47650], [47651, 49974], [49975, 51250], [51251, 52804], [52805, 54658],
  [54659, 55207], [55208, 55373], [55374, 55465], [55466, 55468], [55469, 65534]
];
const sk_colors = [0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1];

for (let i = 0; i < sk_groups.length; i++) {
  const [lo, hi] = sk_groups[i];
  const loP = lo / ADC_MAX_SK;
  const hiP = hi / ADC_MAX_SK;
  const fr = (loP + hiP) / 2;
  const clr = sk_colors[i] === 0 ? band_color(fr) : [.843,.259,.259]; // green=0, red=1 → storegreen/red
  // Actually stored_groups use red/green alternating, not ramp. Let me re-read...
  // Yes, stored: nextIsGreen ? band_color(fr) : [.843,.259,.259]; starting nextIsGreen=true
  // So green=index0, red=index1, green=2, red=3, etc.
  const color = sk_colors[i] === 0 ? band_color(fr) : [0.843, 0.259, 0.259];
  console.log(`SK group ${i+1}: bottom=${CSSPercent(loP*100).replace('%','').replace('%','')}, height=${CSSPercent((hiP-loP)*100).replace('%','').replace('%','')}, top=${CSSAngle(css2top(hi))}, color=rgb(${Math.round(color[0]*255)},${Math.round(color[1]*255)},${Math.round(color[2]*255)})`);
  console.log(`  CSS: bottom: ${CSSPercent(lo/ADC_MAX_SK*100)}; height: ${CSSPercent((hi-lo)/ADC_MAX_SK*100)}; top: ${CSSAngle(css2top(hi))};`);
}

console.log("\n=== SK PID TARGETS (servo=0) ===");
const targets_36_29 = [[36,50],[35,50],[33,50],[31.5,50],[29.3,50]];
for (const [pid_r, max_val] of targets_36_29) {
  console.log(`${pid_r}:${max_val} → height: ${CSSPercent((pid_r / max_val) * 100)}, top: ${CSSAngle(css2top(0))}`);
}

console.log("\n=== SK PID2 TARGETS (servo=sk_pid2_offset) ===");
for (const [pid_r, max_val] of targets_36_29) {
  console.log(`${pid_r}:${max_val} → height: ${CSSPercent((pid_r / max_val) * 100)}, top: ${CSSAngle(css2top(sk_pid2_offset))}`);
}

console.log("\n=== FIRE REAL TARGETS (servo=0, same as SK) ===");
for (const [pid_r, max_val] of targets_36_29) {
  console.log(`${pid_r}:${max_val} → height: ${CSSPercent((pid_r / max_val) * 100)}, top: ${CSSAngle(css2top(0))}`);
}

console.log("\n=== FIRE PID TARGETS (servo=fire_pid_offset) ===");
for (const [pid_r, max_val] of targets_36_29) {
  console.log(`${pid_r}:${max_val} → height: ${CSSPercent((pid_r / max_val) * 100)}, top: ${CSSAngle(css2top(fire_pid_offset))}`);
}

console.log("\n=== FIRE PREDICT TARGETS (servo=fire_predict_offset) ===");
for (const [pid_r, max_val] of targets_36_29) {
  console.log(`${pid_r}:${max_val} → height: ${CSSPercent((pid_r / max_val) * 100)}, top: ${CSSAngle(css2top(fire_predict_offset))}`);
}
console.log(`99:50 → height: ${CSSPercent((99/50)*100)}, top: ${CSSAngle(css2top(fire_predict_offset))}`);

console.log("\n=== COMP STORED GROUPS (ADC_MAX=1254) ===");
const comp_groups = [
  [0, 3790], [3791, 5106], [5107, 6695], [6696, 1257], [10741, 12122],
  [12123, 12352], [12353, 12423], [12424, 12540]
];

for (let i = 0; i < comp_groups.length; i++) {
  const [lo, hi] = comp_groups[i];
  const loP = (lo / ADC_MAX_COMP) * 100;
  const hiP = (hi / ADC_MAX_COMP) * 100;
  const heightP = Math.max(0, hiP - loP);
  const midVal = Math.min(65535, (lo + hi) / 2);
  const fr = midVal / 65535;
  const color = i % 2 === 0 ? band_color(fr) : [0.843, 0.259, 0.259];
  const rgb = `rgb(${Math.round(color[0]*255)},${Math.round(color[1]*255)},${Math.round(color[2]*255)})`;
  console.log(`Comp group ${i+1}: bottom: ${loP.toFixed(2)}%, height: ${heightP.toFixed(2)}%, top: ${css2top(hi).toFixed(4)}deg, color=${rgb}`);
}

console.log("\n=== COMP TARGETS (servo=competition_offset) ===");
const comp_targets = [
  [37,90], [36,90], [33.5,90], [53,44], [37,110], [30,90], [34.5,100], [55,30]
];
const comp_names = ['52~37:90','52~36:90','52~33.5:90','52.5~53:44','57~37:110','57.5~30:90','60~34.5:100','48.5~55:30'];
for (let i = 0; i < comp_targets.length; i++) {
  const [r, max_val] = comp_targets[i];
  console.log(`${comp_names[i]} → height: ${CSSPercent((r / max_val) * 100)}, top: ${CSSAngle(css2top(competition_offset))}`);
}
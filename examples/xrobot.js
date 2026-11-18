// XRobot JavaScript / Node.js 调用示例（零依赖，用内置 fetch）
// Node 18+ 直接运行；浏览器里把 HOST 改成机器人 IP 也可（中枢已开 CORS）

const HOST = '127.0.0.1';   // 设备上/adb forward；局域网填机器人 IP
const PORT = 8899;
const BASE = `http://${HOST}:${PORT}`;

async function call(command, params = {}) {
  const url = new URL(`${BASE}/${command}`);
  Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));
  const resp = await fetch(url.toString());
  return await resp.json();
}

const XRobot = {
  move: (dist = 30, speed = 8) => call('move', { dist, speed }),   // speed 负=后退
  turn: (angle = 90, speed = 60) => call('turn', { angle, speed }), // angle 负=右转
  head: (angle = 0) => call('head', { angle }),
  stop: () => call('stop'),
  gotoColor: (color = 'red') => call('goto', { color }),
  ping: () => call('ping'),
  status: () => call('status'),
  queue: () => call('queue'),
};

// 演示
(async () => {
  console.log('ping:', await XRobot.ping());
  console.log('move:', await XRobot.move(30, 10));
  console.log('status:', await XRobot.status());
})();

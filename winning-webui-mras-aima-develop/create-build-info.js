import { execSync } from 'node:child_process';
import { existsSync, unlinkSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function prependZero(num) {
  if (num > 0 && num < 10) return `0${num}`;
  return num;
}

const date = new Date();

const timeFormat =
  [date.getFullYear(), prependZero(date.getMonth() + 1), prependZero(date.getDate())].join('-') +
  ' ' +
  [
    prependZero(date.getHours()),
    prependZero(date.getMinutes()),
    prependZero(date.getSeconds()),
  ].join(':');

const buildInfo = {
  timestamp: date.getTime(),
  timeFormat,
};

// process 信息
try {
  buildInfo.process = {
    arch: process.arch,
    platform: process.platform,
    version: process.version,
  };
} catch (e) {
  // console.log(e)
}

// os 信息
try {
  buildInfo.os = {
    arch: os.arch(),
    platform: os.platform(),
    type: os.type(),
    version: os.version(),
  };
} catch (e) {
  // console.log(e)
}

// 分支信息
try {
  const branch = execSync('git name-rev --name-only HEAD', { encoding: 'utf8' }).trim();
  const branch2 = execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf8' }).trim();

  const msgList = execSync('git rev-list --format="[%ad]%s" --max-count=5 HEAD', {
    encoding: 'utf8',
  })
    .trim()
    .split(/\n+/);
  const msgList2 = [];
  for (let i = 0; i < msgList.length; i += 2) {
    msgList2.push(`${msgList[i]} ${msgList[i + 1]}`);
  }

  buildInfo.git = {
    branch: branch2,
    branch2: branch,
    reflogs: execSync(`git reflog show ${branch} --max-count=5`, { encoding: 'utf8' })
      .trim()
      .split(/\n+/),
    hash: execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim(),
    shortHash: execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim(),
    lastCommitTimeAndMessage: execSync('git rev-list --format="[%ad]%s" --max-count=1 HEAD', {
      encoding: 'utf8',
    }).trim(),
    latestCommitTimeAndMessage: msgList2,
  };
} catch (e) {}

// 写入文件
try {
  const buildInfoPath = path.resolve(import.meta.dirname, 'public', 'build-info.json');

  if (existsSync(buildInfoPath)) {
    unlinkSync(buildInfoPath);
  }

  writeFileSync(buildInfoPath, JSON.stringify(buildInfo), 'utf8');
} catch (e) {
  // console.log(e)
}

// 确保脚本总是以成功状态退出，即使出错也不影响构建
process.exit(0);

import test from 'node:test'
import assert from 'node:assert/strict'

import { latestPendingQuestionId, projectAutonomousActivities } from '../src/domain/autonomousActivity.ts'

const base = { turnId: 'TURN_1', iteration: 1, createdAt: '2026-08-10T00:00:00Z' }

test('merges model start and public analysis into one stable item', () => {
  const activities = projectAutonomousActivities({
    turnId: 'TURN_1',
    processEvents: [
      { ...base, seq: 1, eventType: 'MODEL_STARTED', status: 'RUNNING' },
      { ...base, seq: 2, eventType: 'ANALYSIS', status: 'SUCCEEDED', problemUnderstanding: '核对科室漏数' },
    ],
  })

  assert.equal(activities.length, 1)
  assert.equal(activities[0].id, 'model:TURN_1:1')
  assert.equal(activities[0].status, 'SUCCEEDED')
  assert.equal(activities[0].analysis.problemUnderstanding, '核对科室漏数')
})

test('merges tool call and observation while keeping distinct calls separate', () => {
  const activities = projectAutonomousActivities({
    turnId: 'TURN_1',
    processEvents: [
      { ...base, seq: 1, eventType: 'TOOL_CALL', status: 'RUNNING', toolCallId: 'CALL_1', tool: 'query_indicator_data', arguments: { sql: 'SELECT 1' } },
      { ...base, seq: 2, eventType: 'OBSERVATION', status: 'SUCCEEDED', toolCallId: 'CALL_1', tool: 'query_indicator_data', durationMs: 18, evidenceId: 'EV_1' },
      { ...base, seq: 3, eventType: 'TOOL_CALL', status: 'RUNNING', toolCallId: 'CALL_2', tool: 'query_indicator_data' },
    ],
  })

  assert.deepEqual(activities.map((item) => item.id), ['tool:CALL_1', 'tool:CALL_2'])
  assert.equal(activities[0].durationMs, 18)
  assert.equal(activities[0].evidenceId, 'EV_1')
  assert.equal(activities[1].status, 'RUNNING')
})

test('deduplicates repeated polling events by seq and preserves reply order', () => {
  const repeated = { ...base, seq: 3, eventType: 'STAGE_REPLY', status: 'SUCCEEDED', answer: '阶段结果' }
  const activities = projectAutonomousActivities({
    turnId: 'TURN_1',
    processEvents: [repeated, repeated, { ...base, seq: 4, eventType: 'CONCLUSION', status: 'SUCCEEDED', conclusion: '最终结论' }],
  })

  assert.deepEqual(activities.map((item) => item.id), ['reply:TURN_1:3', 'reply:TURN_1:4'])
  assert.deepEqual(activities.map((item) => item.kind), ['REPLY', 'CONCLUSION'])
})

test('uses the latest duplicate event value without adding another row', () => {
  const activities = projectAutonomousActivities({
    turnId: 'TURN_1',
    processEvents: [
      { ...base, seq: 1, eventType: 'TOOL_CALL', status: 'RUNNING', toolCallId: 'CALL_1' },
      { ...base, seq: 1, eventType: 'TOOL_CALL', status: 'RUNNING', toolCallId: 'CALL_1', summary: '正在查询' },
    ],
  })

  assert.equal(activities.length, 1)
  assert.equal(activities[0].summary, '正在查询')
})

test('only exposes the latest pending question input', () => {
  const turns = [{
    turnId: 'TURN_1',
    processEvents: [
      { ...base, seq: 1, eventType: 'QUESTION', status: 'WAITING_USER', question: '旧问题' },
      { ...base, seq: 2, eventType: 'QUESTION', status: 'WAITING_USER', question: '最新问题' },
    ],
  }]

  assert.equal(latestPendingQuestionId(turns, [], 'WAITING_USER'), 'reply:TURN_1:2')
  assert.equal(latestPendingQuestionId(turns, [], 'READY'), '')
})

test('marks unfinished model and tool items failed when a turn stops', () => {
  const activities = projectAutonomousActivities({
    turnId: 'TURN_1',
    processEvents: [
      { ...base, seq: 1, eventType: 'MODEL_STARTED', status: 'RUNNING' },
      { ...base, seq: 2, eventType: 'TOOL_CALL', status: 'RUNNING', toolCallId: 'CALL_1' },
      { ...base, seq: 3, eventType: 'STOP', status: 'FAILED', summary: '动作无效' },
    ],
  })

  assert.deepEqual(activities.map((item) => item.status), ['FAILED', 'FAILED', 'FAILED'])
})

test('marks an earlier invalid model attempt completed when the turn returns a response', () => {
  const activities = projectAutonomousActivities({
    turnId: 'TURN_1',
    processEvents: [
      { ...base, seq: 1, eventType: 'MODEL_STARTED', status: 'RUNNING' },
      { ...base, seq: 2, iteration: 2, eventType: 'ANALYSIS', status: 'SUCCEEDED' },
      { ...base, seq: 3, iteration: 2, eventType: 'RESPONSE', status: 'SUCCEEDED', answer: '完整SQL原文' },
    ],
  })

  assert.deepEqual(activities.map((item) => item.status), ['SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED'])
})

import { ref } from 'vue'

const executionTaskCache = new WeakMap()

function sortAgentSteps(steps) {
  return [...steps].sort((left, right) => {
    const leftIndex = left?.stepIndex ?? 0
    const rightIndex = right?.stepIndex ?? 0
    return leftIndex - rightIndex
  })
}

function extractPlanPayloadFromSteps(steps) {
  const orderedSteps = sortAgentSteps(steps || [])
  for (let index = orderedSteps.length - 1; index >= 0; index--) {
    const step = orderedSteps[index]
    if (step?.stepType !== 'plan' || !step.arguments) {
      continue
    }
    return step.arguments
  }
  return null
}

function extractPlanPayloadsFromSteps(steps) {
  return sortAgentSteps(steps || [])
    .filter((step) => step?.stepType === 'plan' && step.arguments)
    .map((step) => step.arguments)
}

function getSourcePlan(source) {
  const mergedTasks = []
  const indexByKey = new Map()
  const appendTasks = (tasks, appended = false) => {
    if (!Array.isArray(tasks)) {
      return
    }
    tasks.forEach((task, taskIndex) => {
      if (!task || typeof task !== 'object') {
        return
      }
      const normalizedTask = {
        key: task.key || `task-${mergedTasks.length + taskIndex + 1}`,
        title: task.title || '',
        description: task.description || '',
        planOrigin: appended ? 'appended' : 'initial'
      }
      const existingIndex = indexByKey.get(normalizedTask.key)
      if (existingIndex === undefined) {
        indexByKey.set(normalizedTask.key, mergedTasks.length)
        mergedTasks.push(normalizedTask)
        return
      }
      const previous = mergedTasks[existingIndex]
      mergedTasks[existingIndex] = {
        ...previous,
        title: normalizedTask.title || previous.title,
        description: normalizedTask.description || previous.description,
        planOrigin: previous.planOrigin || normalizedTask.planOrigin
      }
    })
  }

  extractPlanPayloadsFromSteps(source?.agentSteps || source?.steps || []).forEach((payload, payloadIndex) => {
    appendTasks(payload?.tasks, payloadIndex > 0)
  })

  if (Array.isArray(source?.agentPlan) && source.agentPlan.length) {
    appendTasks(source.agentPlan, mergedTasks.length > 0)
  }

  if (mergedTasks.length) {
    return mergedTasks
  }
  const planPayload = extractPlanPayloadFromSteps(source?.agentSteps || source?.steps || [])
  return Array.isArray(planPayload?.tasks) ? planPayload.tasks : []
}

function getSourceCurrentActionKey(source) {
  if (source?.currentActionKey) {
    return source.currentActionKey
  }
  const steps = sortAgentSteps(source?.agentSteps || source?.steps || [])
  for (let index = steps.length - 1; index >= 0; index--) {
    const taskKey = steps[index]?.arguments?.taskKey
    if (typeof taskKey === 'string' && taskKey) {
      return taskKey
    }
  }
  const planPayload = extractPlanPayloadFromSteps(steps)
  return typeof planPayload?.currentActionKey === 'string' ? planPayload.currentActionKey : ''
}

function getSourceCompletedTaskKeys(source) {
  if (Array.isArray(source?.completedTaskKeys) && source.completedTaskKeys.length) {
    return source.completedTaskKeys.filter((item) => typeof item === 'string' && item)
  }
  return []
}

function buildTaskToolBuckets(source, tasks, steps) {
  const toolCalls = Array.isArray(source?.toolCalls) ? [...source.toolCalls] : []
  const taskBuckets = new Map()
  const orphanTools = []

  for (const task of tasks) {
    taskBuckets.set(task.key, [])
  }

  let toolCursor = 0
  const nextMatchingToolCall = (step) => {
    for (let index = toolCursor; index < toolCalls.length; index++) {
      const candidate = toolCalls[index]
      const candidateTaskKey = candidate?.arguments?.taskKey
      const stepTaskKey = step?.arguments?.taskKey
      const taskMatches = candidateTaskKey && stepTaskKey
        ? candidateTaskKey === stepTaskKey
        : true
      const toolMatches = !step?.toolName || !candidate?.toolName || step.toolName === candidate.toolName
      if (taskMatches && toolMatches) {
        toolCursor = index + 1
        return candidate
      }
    }
    return null
  }

  for (const step of steps) {
    if (step?.stepType !== 'tool_call') {
      continue
    }
    const matchedToolCall = nextMatchingToolCall(step)
    if (!matchedToolCall) {
      continue
    }
    const taskKey = step?.arguments?.taskKey
    if (taskKey && taskBuckets.has(taskKey)) {
      taskBuckets.get(taskKey).push(matchedToolCall)
    } else {
      orphanTools.push(matchedToolCall)
    }
  }

  for (; toolCursor < toolCalls.length; toolCursor++) {
    orphanTools.push(toolCalls[toolCursor])
  }

  return { taskBuckets, orphanTools }
}

function formatAgentStepLabel(step) {
  if (!step) {
    return '未知步骤'
  }

  if (step.stepType === 'tool_call') {
    return step.toolName ? `调用工具 · ${step.toolName}` : '调用工具'
  }
  if (step.stepType === 'plan') {
    return '生成任务计划'
  }
  if (step.stepType === 'finish') {
    return '结束并生成回答'
  }
  if (step.stepType === 'respond_with_gap') {
    return '结束并说明信息缺口'
  }
  return step.stepType || '步骤'
}

function formatExecutionTaskTitle(step) {
  if (!step) {
    return '未知任务'
  }
  if (step.stepType === 'tool_call') {
    return step.toolName ? `调用工具 ${step.toolName}` : '调用工具补充信息'
  }
  if (step.stepType === 'finish') {
    return '整理结论并生成最终回答'
  }
  if (step.stepType === 'respond_with_gap') {
    return '说明信息缺口并结束本轮'
  }
  return formatAgentStepLabel(step)
}

function resolveExecutionStatus(source) {
  if (source?.runStatus) {
    return source.runStatus
  }
  if (source?.status) {
    return source.status
  }
  if (source?.content || source?.finalAnswer) {
    return 'SUCCESS'
  }
  return 'RUNNING'
}

function buildExecutionTaskCacheSignature(source, steps, explicitPlan, currentActionKey, completedTaskKeys, executionStatus, finalContent) {
  const stepSignature = steps.map((step) => [
    step?.stepIndex ?? '',
    step?.stepType ?? '',
    step?.toolName ?? '',
    step?.status ?? '',
    step?.durationMs ?? '',
    step?.arguments?.taskKey ?? ''
  ].join(':')).join('|')
  const planSignature = explicitPlan.map((task) => [
    task?.key ?? '',
    task?.title ?? '',
    task?.description ?? ''
  ].join(':')).join('|')
  const completedSignature = Array.from(completedTaskKeys).join('|')
  return [
    source?.runId ?? '',
    source?.status ?? '',
    source?.runStatus ?? '',
    executionStatus,
    currentActionKey ?? '',
    completedSignature,
    finalContent ? '1' : '0',
    planSignature,
    stepSignature
  ].join('~')
}

function normalizeExecutionTaskStatuses(tasks, executionStatus) {
  if (!Array.isArray(tasks) || !tasks.length) {
    return []
  }

  const terminalStatuses = new Set(['SUCCESS', 'FAILED', 'PARTIAL', 'UNFINISHED'])
  const lastTerminalIndex = tasks.reduce((acc, task, index) => {
    return terminalStatuses.has(task?.status) ? index : acc
  }, -1)

  return tasks.map((task, index) => {
    if (!task || task.status !== 'RUNNING') {
      return task
    }

    const hasLaterTerminalTask = lastTerminalIndex > index
    const runClosed = executionStatus === 'SUCCESS' || executionStatus === 'PARTIAL'
    if (!hasLaterTerminalTask && !runClosed) {
      return task
    }

    const toolCalls = Array.isArray(task.toolCalls) ? task.toolCalls : []
    const rawSteps = Array.isArray(task.rawSteps) ? task.rawSteps : []
    const hasFailedSignal = toolCalls.some((item) => item?.status === 'FAILED') || rawSteps.some((item) => item?.status === 'FAILED')
    const hasSuccessSignal = toolCalls.some((item) => item?.status === 'SUCCESS')
      || rawSteps.some((item) => item?.status === 'SUCCESS')
      || rawSteps.some((item) => item?.stepType === 'tool_call' && item?.status && item.status !== 'FAILED' && item.status !== 'RUNNING')

    return {
      ...task,
      status: hasFailedSignal
        ? 'FAILED'
        : (hasSuccessSignal ? 'SUCCESS' : (runClosed ? 'UNFINISHED' : 'PARTIAL'))
    }
  })
}

function buildExecutionTasks(source) {
  if (!source || typeof source !== 'object') {
    return []
  }

  const steps = sortAgentSteps(source?.agentSteps || source?.steps || [])
  const explicitPlan = getSourcePlan(source)
  const currentActionKey = getSourceCurrentActionKey(source)
  const completedTaskKeys = new Set(getSourceCompletedTaskKeys(source))
  const executionStatus = resolveExecutionStatus(source)
  const finalContent = source?.content || source?.finalAnswer || ''
  const rawActionSteps = steps.filter((step) => step?.stepType !== 'plan')
  const cacheSignature = buildExecutionTaskCacheSignature(
    source,
    steps,
    explicitPlan,
    currentActionKey,
    completedTaskKeys,
    executionStatus,
    finalContent
  )
  const cached = executionTaskCache.get(source)

  if (cached?.signature === cacheSignature && Array.isArray(cached.tasks)) {
    return cached.tasks
  }

  if (explicitPlan.length) {
    const currentTaskIndex = explicitPlan.findIndex((task) => task?.key === currentActionKey)
    const finalTaskIndex = explicitPlan.length - 1
    const runFinished = executionStatus === 'SUCCESS' || executionStatus === 'PARTIAL'
    const seededTasks = explicitPlan.map((task, index) => {
      const taskSteps = rawActionSteps.filter((step) => step?.arguments?.taskKey === task.key)
      const matchedStep = taskSteps.at(-1)
      const isCurrent = Boolean(currentActionKey && currentActionKey === task.key)
      const hasSuccessfulActivity = taskSteps.some((step) => step?.status && step.status !== 'FAILED' && step.stepType !== 'plan')
      const hasTerminalStep = taskSteps.some((step) => step?.stepType === 'finish' || step?.stepType === 'respond_with_gap')
      let inferredStatus = 'PENDING'

      if (completedTaskKeys.has(task.key) || hasTerminalStep || (runFinished && finalContent && index === finalTaskIndex)) {
        inferredStatus = 'SUCCESS'
      } else if (matchedStep?.status === 'FAILED') {
        inferredStatus = matchedStep.status
      } else if (matchedStep?.status === 'PARTIAL') {
        inferredStatus = matchedStep.status
      } else if (matchedStep?.status === 'RUNNING') {
        inferredStatus = matchedStep.status
      } else if (matchedStep?.status && !completedTaskKeys.size) {
        inferredStatus = matchedStep.status
      } else if (isCurrent) {
        inferredStatus = 'RUNNING'
      } else if (runFinished) {
        if (hasSuccessfulActivity || (currentTaskIndex >= 0 && index < currentTaskIndex && taskSteps.length)) {
          inferredStatus = 'PARTIAL'
        } else {
          inferredStatus = 'UNFINISHED'
        }
      }

      return {
        key: task.key || `task-${index + 1}`,
        title: task.title || `任务 ${index + 1}`,
        subtitle: matchedStep ? formatAgentStepLabel(matchedStep) : (task.description || ''),
        detail: matchedStep?.observationSummary || matchedStep?.reason || task.description || '',
        planOrigin: task.planOrigin || 'initial',
        status: inferredStatus,
        durationMs: matchedStep?.durationMs ?? null,
        toolName: matchedStep?.toolName || '',
        arguments: matchedStep?.arguments || null,
        rawSteps: taskSteps,
        order: index + 1
      }
    })
    const { taskBuckets, orphanTools } = buildTaskToolBuckets(source, seededTasks, rawActionSteps)
    const normalizedTasks = normalizeExecutionTaskStatuses(
      seededTasks.map((task) => ({
        ...task,
        toolCalls: taskBuckets.get(task.key) || []
      })),
      executionStatus
    )
    const result = normalizedTasks.map((task) => ({
      ...task,
      orphanTools
    }))
    executionTaskCache.set(source, { signature: cacheSignature, tasks: result })
    return result
  }

  const tasks = [
    {
      key: 'plan',
      title: '理解问题并生成执行计划',
      subtitle: source?.userGoal || '',
      detail: steps[0]?.reason || '',
      planOrigin: 'initial',
      status: steps.length || finalContent ? 'SUCCESS' : (executionStatus === 'RUNNING' ? 'RUNNING' : 'PENDING')
    }
  ]

  for (const step of steps) {
    tasks.push({
      key: `step-${step.stepIndex ?? tasks.length}`,
      title: formatExecutionTaskTitle(step),
      subtitle: formatAgentStepLabel(step),
      detail: step.observationSummary || step.reason || '',
      planOrigin: 'initial',
      status: step.status || 'PENDING',
      durationMs: step.durationMs ?? null,
      toolName: step.toolName || '',
      arguments: step.arguments || null,
      rawSteps: [step]
    })
  }

  tasks.push({
    key: 'answer',
    title: '输出最终回答',
    subtitle: finalContent ? '已生成结果' : '等待汇总执行结果',
    detail: '',
    planOrigin: 'initial',
    status: finalContent
      ? 'SUCCESS'
      : (executionStatus === 'RUNNING' ? 'RUNNING' : (executionStatus === 'PARTIAL' ? 'PARTIAL' : 'PENDING'))
  })

  const seededTasks = tasks.map((task, index) => ({
    ...task,
    order: index + 1
  }))
  const { taskBuckets, orphanTools } = buildTaskToolBuckets(source, seededTasks, rawActionSteps)
  const normalizedTasks = normalizeExecutionTaskStatuses(
    seededTasks.map((task) => ({
      ...task,
      toolCalls: taskBuckets.get(task.key) || []
    })),
    executionStatus
  )
  const result = normalizedTasks.map((task) => ({
    ...task,
    orphanTools
  }))
  executionTaskCache.set(source, { signature: cacheSignature, tasks: result })
  return result
}

function getExecutionProgressMeta(source) {
  const tasks = buildExecutionTasks(source)
  if (!tasks.length) {
    return {
      summary: '0 / 0',
      headline: '等待开始',
      subline: '还没有生成可展示的执行任务',
      ratio: 0
    }
  }

  const successCount = tasks.filter((task) => task.status === 'SUCCESS').length
  const runningCount = tasks.filter((task) => task.status === 'RUNNING').length
  const partialCount = tasks.filter((task) => task.status === 'PARTIAL').length
  const unfinishedCount = tasks.filter((task) => task.status === 'UNFINISHED').length
  const failedCount = tasks.filter((task) => task.status === 'FAILED').length
  const ratio = Math.round((successCount / tasks.length) * 100)

  let headline = `已完成 ${successCount} / ${tasks.length}`
  let subline = '正在按计划推进'
  if (failedCount) {
    headline = `${failedCount} 个任务失败`
    subline = successCount
      ? `已完成 ${successCount} 个任务，仍有失败步骤需要处理`
      : '执行中出现失败步骤'
  } else if (runningCount) {
    headline = `进行中 · ${successCount} / ${tasks.length}`
    subline = partialCount
      ? `还有 ${partialCount} 个任务已执行但未完全收尾`
      : '当前任务正在执行'
  } else if (unfinishedCount) {
    headline = `已完成 ${successCount} / ${tasks.length}`
    subline = `还有 ${unfinishedCount} 个任务未完成`
  } else if (partialCount) {
    headline = `已完成 ${successCount} / ${tasks.length}`
    subline = `${partialCount} 个任务已执行但还没有完整收尾`
  } else if (successCount === tasks.length) {
    headline = `全部完成 · ${tasks.length} / ${tasks.length}`
    subline = '整条执行链路已经闭环'
  }

  return {
    summary: `${successCount} / ${tasks.length} 已完成`,
    headline,
    subline,
    ratio
  }
}

function getCurrentExecutionTask(source) {
  const tasks = buildExecutionTasks(source)
  const executionStatus = resolveExecutionStatus(source)
  const activeTask = tasks.find((task) => task.status === 'RUNNING')
    || tasks.find((task) => task.status === 'PENDING')
  if (activeTask) {
    return activeTask
  }
  if (executionStatus === 'SUCCESS' || executionStatus === 'PARTIAL') {
    return null
  }
  return tasks[tasks.length - 1] || null
}

function isCurrentExecutionTask(source, task) {
  if (!task) {
    return false
  }
  const currentTask = getCurrentExecutionTask(source)
  return Boolean(currentTask && currentTask.key === task.key)
}

function getExecutionOrphanTools(source) {
  const tasks = buildExecutionTasks(source)
  return tasks[0]?.orphanTools || []
}

function getToolStatusClass(status) {
  return {
    SUCCESS: 'success',
    RUNNING: 'warning',
    FAILED: 'danger',
    PARTIAL: 'warning',
    UNFINISHED: 'info',
    PENDING: 'info'
  }[status] || 'info'
}

function formatToolStatus(status) {
  return {
    SUCCESS: '成功',
    RUNNING: '执行中',
    FAILED: '失败',
    PARTIAL: '已执行未收尾',
    UNFINISHED: '未完成',
    PENDING: '待执行'
  }[status] || (status || '未知')
}

function formatToolArguments(argumentsObject) {
  return JSON.stringify(argumentsObject, null, 2)
}

function formatToolHeadline(toolCall) {
  const source = toolCall?.source || ''
  if (source.startsWith('mcp:')) {
    return `已调用 MCP · ${source.slice(4)}`
  }
  return `已调用工具 · ${toolCall?.displayName || toolCall?.toolName || 'unknown'}`
}

function formatToolSubtitle(toolCall) {
  const source = toolCall?.source || ''
  if (source.startsWith('mcp:')) {
    return toolCall?.displayName || toolCall?.toolName || source.slice(4)
  }
  return source || 'builtin'
}

function shortRunId(runId) {
  if (!runId) {
    return ''
  }
  return runId.length <= 16 ? runId : `${runId.slice(0, 12)}...`
}

function getExecutionSourceId(source) {
  return source?.runId || source?.createdAt || source?.userGoal || 'execution'
}

function getTaskExpandStateKey(source, taskKey) {
  return `${getExecutionSourceId(source)}:${taskKey}`
}

function getExecutionPanelStateKey(source) {
  return `${getExecutionSourceId(source)}:panel`
}

export function useExecutionTasks() {
  const rawStepExpandState = ref({})
  const executionPanelExpandState = ref({})

  function isExecutionPanelExpanded(source) {
    if (!source || typeof source !== 'object') {
      return false
    }
    const stateKey = getExecutionPanelStateKey(source)
    const stored = executionPanelExpandState.value[stateKey]
    return stored === undefined ? false : Boolean(stored)
  }

  function toggleExecutionPanel(source) {
    const stateKey = getExecutionPanelStateKey(source)
    executionPanelExpandState.value = {
      ...executionPanelExpandState.value,
      [stateKey]: !isExecutionPanelExpanded(source)
    }
  }

  function isTaskRawStepsExpanded(source, taskKey) {
    return Boolean(rawStepExpandState.value[getTaskExpandStateKey(source, taskKey)])
  }

  function toggleTaskRawSteps(source, taskKey) {
    const stateKey = getTaskExpandStateKey(source, taskKey)
    rawStepExpandState.value = {
      ...rawStepExpandState.value,
      [stateKey]: !rawStepExpandState.value[stateKey]
    }
  }

  return {
    buildExecutionTasks,
    getExecutionProgressMeta,
    getCurrentExecutionTask,
    isCurrentExecutionTask,
    getExecutionOrphanTools,
    isExecutionPanelExpanded,
    toggleExecutionPanel,
    isTaskRawStepsExpanded,
    toggleTaskRawSteps,
    getToolStatusClass,
    formatToolStatus,
    formatToolArguments,
    formatToolHeadline,
    formatToolSubtitle,
    formatAgentStepLabel,
    shortRunId,
    sortAgentSteps
  }
}

import type { ExperimentsUpdate, ExperimentsView } from '../types'
import { delay, state } from './shared'

export async function mockExperiments(): Promise<ExperimentsView> {
  await delay(100)
  return { ...state.experiments }
}

export async function mockUpdateExperiments(update: ExperimentsUpdate): Promise<ExperimentsView> {
  await delay(120)
  state.experiments = { ...state.experiments, ...update }
  return { ...state.experiments }
}

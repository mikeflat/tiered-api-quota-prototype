import { useDashboard } from '../application/useDashboard.ts'
import { DashboardScreen } from './components/DashboardScreen.tsx'

function App() {
  return <DashboardScreen view={useDashboard()} />
}

export default App

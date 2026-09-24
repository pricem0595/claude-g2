import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, type Plugin } from 'vite'

/**
 * Dev only: the glasses app POSTs the bridge's raw screen dumps here, so real Claude app layouts
 * land on the PC for building parser fixtures. They hold conversation text, so `captures/` is
 * gitignored; scrub anything that becomes a committed fixture.
 */
function captureSink(): Plugin {
  const dir = join(import.meta.dirname, 'captures')
  return {
    name: 'claude-g2-capture-sink',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__capture', (req, res) => {
        if (req.method !== 'POST') {
          res.statusCode = 405
          res.end()
          return
        }
        const chunks: Buffer[] = []
        req.on('data', (c: Buffer) => chunks.push(c))
        req.on('end', () => {
          const label = String(req.headers['x-capture-label'] ?? 'screen').replace(/[^a-z0-9-]/gi, '_').slice(0, 40)
          mkdirSync(dir, { recursive: true })
          const file = join(dir, `${new Date().toISOString().replace(/[:.]/g, '-')}-${label}.xml`)
          writeFileSync(file, Buffer.concat(chunks))
          server.config.logger.info(`[capture] ${file}`)
          res.statusCode = 204
          res.end()
        })
      })
    },
  }
}

export default defineConfig({ plugins: [captureSink()] })

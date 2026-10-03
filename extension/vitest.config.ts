import { defineConfig } from "vitest/config";

export default defineConfig({
  define: { __CORE_API_URL__: JSON.stringify("http://localhost:8080"), __PANEL_OPEN__: false },
  test: {
    environment: "jsdom",
    include: ["tests/unit/**/*.test.ts"],
  },
});

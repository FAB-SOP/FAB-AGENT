import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
export default defineConfig({plugins:[react(),tailwindcss()],server:{host:'127.0.0.1',port:8770,strictPort:true,proxy:{'/api':{target:process.env.API_TARGET||'http://127.0.0.1:8090',changeOrigin:true}}},build:{sourcemap:true}});

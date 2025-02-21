export default {
  input: 'dist/esm/index.js',
  output: [
    {
      file: 'dist/plugin.js',
      format: 'iife',
      name: 'capacitorVoiceRec',
      globals: {
        '@capacitor/core': 'capacitorExports',
        'get-blob-duration': 'getBlobDuration',
        'idb': 'idb',
      },
      sourcemap: true,
      inlineDynamicImports: true,
    },
    {
      file: 'dist/plugin.cjs.js',
      format: 'cjs',
      sourcemap: true,
      inlineDynamicImports: true,
    },
  ],
  external: ['@capacitor/core', 'get-blob-duration', 'idb'],
};

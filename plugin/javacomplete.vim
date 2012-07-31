if exists('g:loaded_javacompleteplugin')
  finish
endif

function! s:initialize()
  command! -bang -narg=0 JavaCompleteReindexAll call javacomplete#ReindexAllSources()
  command! -bang -narg=0 JavaCompleteReindexFile call javacomplete#ReindexFile()
  command! -bang -narg=0 JavaCompleteGoToDefinition call javacomplete#GoToDefinition()
  command! -bang -narg=0 JavaCompleteRestartWithFolder call javacomplete#RestartWithFolder()
  command! -bang -narg=0 JavaCompleteRestart call javacomplete#Restart()
  command! -bang -narg=0 JavaCompleteReplaceWithImport call javacomplete#ReplaceWithImport()
  command! -range -bang -narg=0 JavaCompleteSortImports :<line1>,<line2>call javacomplete#SortImports()
endfunction

call s:initialize()

let g:loaded_javacompleteplugin = 1

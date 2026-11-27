import difftest as d, sys
T=[]
def t(name, lines, **k): T.append((name, lines, k))
P3='wr1,wr2,wr3'
for m in ['wavg2b','wavg2br','wavg2h','wavg2hr','wabsdiffb','wabsdiffh','wabsdiffw','wunpckelsb','wunpckelsh','wunpckelsw','wunpckehsb','wunpckehsh','wunpckehsw','wunpckelub','wunpckehuw']:
    t(m, [f'{m} wr1,wr2' if 'unpck' in m else f'{m} {P3}'])
for m in ['wpackdss','wpackhss','wpackwss','wpackhus','wpackwus']: t(m,[f'{m} {P3}'])
for m in ['tmovmskb','tmovmskh','tmovmskw']: t(m,[f'{m} r1,wr2'])
for m in ['wmacs','wmacu','wmacsz','wmacuz','wmadds','wmaddu']: t(m,[f'{m} {P3}'])
for m in ['wacc'+x for x in 'bhw']: t(m,[f'{m} wr1,wr2'])
for x in ['','ph','bb','bt','tb','tt']: t('tmia'+x,['and r2,r2,#255','and r3,r3,#255',f'tmia{x} wr1,r2,r3'])
for op in ['srl','sll','sra']:
    for sz in 'hwd':
        lim={'h':15,'w':31,'d':63}[sz]; t(f'w{op}{sz} (reg count)',[f'and r4,r4,#{lim}','wxor wr3,wr3,wr3','tinsrb wr3,r4,#0',f'w{op}{sz} wr1,wr2,wr3'])
        t(f'w{op}{sz} (big count)',['wxor wr3,wr3,wr3','tinsrb wr3,r4,#0','tinsrb wr3,r4,#1',f'w{op}{sz} wr1,wr2,wr3'], ref=None, flagmask=0) if False else None
        t(f'w{op}{sz}g',[f'w{op}{sz}g wr1,wr2,wcgr1'])
# memory forms vs equivalent reference sequences
t('wldrd [r11,+r2]',['mov r2,#16','wldrd wr1,[r11,+r2]'],ref=['mov r2,#16','add r12,r11,r2','wldrd wr1,[r12]'])
t('wldrd [r11,-r2]',['mov r2,#24','add r11,r11,#32','wldrd wr1,[r11,-r2]'],ref=['mov r2,#24','add r11,r11,#32','sub r12,r11,r2','wldrd wr1,[r12]'])
t('wldrd [r11,+r2]!',['mov r2,#16','wldrd wr1,[r11,+r2]!','ldr r4,=init','sub r3,r11,r4'],ref=['mov r2,#16','add r11,r11,r2','wldrd wr1,[r11]','ldr r4,=init','sub r3,r11,r4'])
t('wldrd [r11],+r2',['mov r2,#16','wldrd wr1,[r11],+r2','ldr r4,=init','sub r3,r11,r4'],ref=['mov r2,#16','wldrd wr1,[r11]','add r11,r11,r2','ldr r4,=init','sub r3,r11,r4'])
t('wldrd [r11],-r2',['mov r2,#16','add r11,r11,#40','wldrd wr1,[r11],-r2'],ref=['mov r2,#16','add r11,r11,#40','wldrd wr1,[r11]','sub r11,r11,r2'])
t('wstrd [r11,+r2]',['mov r2,#16','wstrd wr3,[r11,+r2]','wldrd wr1,[r11,#16]'],ref=['mov r2,#16','add r12,r11,r2','wstrd wr3,[r12]','wldrd wr1,[r11,#16]'])
t('wstrd [r11],+r2',['mov r2,#16','wstrd wr3,[r11],+r2','wldrd wr1,[r11,#-16]','sub r11,r11,r2'],ref=['mov r2,#16','wstrd wr3,[r11]','add r11,r11,r2','wldrd wr1,[r11,#-16]','sub r11,r11,r2'])
t('wldrw wcgr',['wldrw wcgr1,[r11,#152]'],ref=['ldr r6,[r11,#152]','tmcr wcgr1,r6'])

t('wstrw wcgr',['and r2,r2,#63','tmcr wcgr0,r2','wstrw wcgr0,[r11,#16]','ldr r3,[r11,#16]'],ref=['and r2,r2,#63','tmcr wcgr0,r2','str r2,[r11,#16]','ldr r3,[r11,#16]'])
# big offsets
t('wldrw [r11,#-600] style',['add r11,r11,#700','wldrw wr1,[r11,#-600]'],ref=['add r11,r11,#700','sub r12,r11,#600','wldrw wr1,[r12]'])
sel = sys.argv[1:]
bad=0
for name, lines, k in T:
    if sel and not any(x in name for x in sel): continue
    r = d.test(lines, **k)
    if r != 'ok': bad+=1
    print(f'{name:28s} {r}')
print('failures:', bad)

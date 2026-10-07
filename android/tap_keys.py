"""Types on Gboard's QWERTY keys over adb, for driving the field probe while the keyboard is up.

    pyrun python android/tap_keys.py <serial> "t,e,h, ,~the, ,s,a,m" [gap_s]

Tokens are comma-separated: letters are tapped in turn, `~word` swipes across the word's keys. Key positions are
those of Gboard on the 1440x3120 S25 in portrait with the number row off; they are not discovered.
"""
S=sys.argv[1]; seq=sys.argv[2]; gap=float(sys.argv[3]) if len(sys.argv)>3 else 0.7
rows=[("qwertyuiop",[50,141,232,322,414,506,597,688,780,872],2278),("asdfghjkl",[95,187,278,368,460,552,643,735,826],2462),("zxcvbnm",[186,277,368,459,551,643,735],2649)]
pos={}
for letters,xs,y in rows:
    for c,x in zip(letters,xs): pos[c]=(int(x*1.56),y)
pos[' ']=(int(505*1.56),2836)
def tap(c):
    x,y=pos[c]; subprocess.run(["adb","-s",S,"shell","input","tap",str(x),str(y)])
def swipe(word):
    pts=[pos[c] for c in word]
    # one continuous swipe through the letters
    (x0,y0)=pts[0]; (x1,y1)=pts[-1]
    subprocess.run(["adb","-s",S,"shell","input","swipe",str(x0),str(y0),str(x1),str(y1),"400"])
for tok in seq.split(","):
    if tok.startswith("~"): swipe(tok[1:])
    else:
        for c in tok: tap(c); time.sleep(gap)
    time.sleep(gap)

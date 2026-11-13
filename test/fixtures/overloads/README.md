# Issue #3: equal-distance overloads

`Validator.java` declares the three-argument overload on line 23. The two JaCoCo
method entries have executable lines 20 and 26, each three lines away. The retained
regression XML deliberately assigns 0/40 covered instructions to the two-argument
overload and 80/80 covered instructions plus 8/8 branches to the three-argument
overload, representing the reported failure. These are fixture counters, not a
claim that compiling this reduced source produces 80 instructions.

`OverloadedCoverageRegressionTest` checks both XML orders and both overloads. It
fails against the original production matcher with expected 100% but actual 0%.

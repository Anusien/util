#!/usr/bin/perl
$script = `readlink -f $0`;
chomp($dir = `dirname $script`);
`ln -sf $dir/libsquallmmap.so.1.0.1 /usr/lib/libsquallmmap.so`;

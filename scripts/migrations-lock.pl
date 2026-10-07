#!/usr/bin/env perl
# 동결된 Flyway 마이그레이션의 잠금(migrations.lock) 검사 · 갱신. 한 번 잠긴 마이그레이션은 제자리에서 고치지 않는다 — 새 V 파일을 더한다
# (docs/schema-management.md 「When you may edit a migration」). 배포됐을 수 있는 파일이 조용히 바뀌어 체크섬이 어긋나는 일을 장치로 막는다.
#
#   perl scripts/migrations-lock.pl --check               # 쓰지 않고 검사, 어긋나면 종료 코드 1 (./gradlew build 의 MigrationLockTest 가 부른다)
#   perl scripts/migrations-lock.pl                       # 새 파일만 잠금에 더한다 (기존 줄은 건드리지 않는다 · 순서가 어긋난 새 파일은 거절)
#   perl scripts/migrations-lock.pl --rewrite <경로>...    # 기존 줄의 해시 변경(또는 사라진 파일의 줄 삭제)을 명시적으로 받아 준다 — 로컬 전용일 때만, CHANGELOG 에 적는다
#   perl scripts/migrations-lock.pl --regenerate          # 전부 다시 만든다 (잠금이 무의미해진다) — new-project.sh 가 찍은 프로젝트의 첫 잠금을 만들 때만 쓴다
#   --root <디렉토리>                                      # 레포 루트 (기본: 이 스크립트의 부모의 부모) — 테스트용
#
# 잠그는 것: <루트>/**/src/main/resources/db/migration/<postgresql|mysql>/V<14자리>__*.sql (test 리소스 · build 출력은 제외).
# 줄 모양: "경로  sha256" (경로순 정렬), 첫 줄 "baseline <버전>" = 업그레이드 테스트의 기준선(MigrationUpgradeIntegrationTest) — 파일을 더해도 움직이지 않는다.
# 의존은 perl 5.14+ 의 core 모듈(Digest::SHA)뿐이다 — new-project.sh · build-capabilities.pl 과 같은 전제(perl).
use strict;
use warnings;
use utf8;
use Digest::SHA;
use File::Basename qw(dirname);
use File::Find;
use File::Spec;
use Cwd qw(abs_path);

binmode(STDOUT, ':encoding(UTF-8)');
binmode(STDERR, ':encoding(UTF-8)');

my $LOCK = 'migrations.lock';
my $CMD = 'perl scripts/migrations-lock.pl';
my %SKIP = map { $_ => 1 } qw(build .gradle .git .kotlin node_modules out .claude);   # MigrationFileRules 의 SKIP_DIRS 와 같다
my $HEADER = <<'TXT';
# 동결된 Flyway 마이그레이션의 잠금 — 손으로 고치지 않는다. 설명: docs/schema-management.md 「When you may edit a migration」
#   perl scripts/migrations-lock.pl --check    검사 (./gradlew build 가 MigrationLockTest 로 같은 검사를 한다)
#   perl scripts/migrations-lock.pl            새 마이그레이션 파일을 잠금에 더한다
# baseline = MigrationUpgradeIntegrationTest 가 "이 버전까지 migrate → 데이터 삽입 → 최신까지 migrate" 로 시작하는 기준선
TXT

sub slurp_bytes {
  my ($path) = @_;
  open(my $fh, '<:raw', $path) or die "읽을 수 없다: $path ($!)\n";
  local $/;
  my $data = <$fh>;
  close($fh);
  return $data;
}

sub sha256 { return Digest::SHA::sha256_hex(slurp_bytes($_[0])); }

# 디스크의 잠글 대상: { 상대경로 => { hash, vendor, version } }
sub scan {
  my ($root) = @_;
  my %found;
  find({
    no_chdir => 1,
    preprocess => sub { return sort @_ },
    wanted => sub {
      my $path = $File::Find::name;
      if (-d $path) {
        return if $path eq $root;
        my $name = (File::Spec->splitpath($path))[2];
        $name = (File::Spec->splitdir($path))[-1] if $name eq '';
        if ($SKIP{$name} || -e "$path/.git") { $File::Find::prune = 1; }   # 루트 아래 .git 이 있는 디렉토리 = 다른 워크트리 · 레포
        return;
      }
      my $rel = File::Spec->abs2rel($path, $root);
      $rel =~ s{\\}{/}g;
      return unless $rel =~ m{(?:^|/)src/main/resources/db/migration/(postgresql|mysql)/V(\d{14})__[^/]+\.sql$};
      $found{$rel} = { vendor => $1, version => $2, hash => sha256($path) };
    },
  }, $root);
  return \%found;
}

# 잠금 파일: { baseline => 버전 | undef, files => { 경로 => 해시 } }
sub read_lock {
  my ($root) = @_;
  my $path = "$root/$LOCK";
  die "$LOCK 이 없다 — 만든다: $CMD --regenerate\n" unless -f $path;
  my ($baseline, %files);
  open(my $fh, '<:encoding(UTF-8)', $path) or die "읽을 수 없다: $path ($!)\n";
  while (my $line = <$fh>) {
    chomp $line;
    next if $line =~ /^\s*(#|$)/;
    if ($line =~ /^baseline\s+(\d{14})\s*$/) { $baseline = $1; next; }
    if ($line =~ /^(\S+)  ([0-9a-f]{64})\s*$/) { $files{$1} = $2; next; }
    die "$LOCK: 읽을 수 없는 줄: $line\n";
  }
  close($fh);
  return { baseline => $baseline, files => \%files };
}

sub write_lock {
  my ($root, $baseline, $files) = @_;
  open(my $fh, '>:encoding(UTF-8)', "$root/$LOCK") or die "쓸 수 없다: $root/$LOCK ($!)\n";
  print $fh $HEADER;
  print $fh "baseline $baseline\n";
  print $fh "$_  $files->{$_}\n" for sort keys %$files;
  close($fh);
}

sub version_of { return $_[0] =~ m{/V(\d{14})__[^/]+\.sql$} ? $1 : undef; }
sub vendor_of  { return $_[0] =~ m{/db/migration/(postgresql|mysql)/} ? $1 : undef; }

# 잠금과 디스크를 맞춰 본다 → { changed => [...], missing => [...], added => [...], out_of_order => [...] }
sub compare {
  my ($lock, $disk) = @_;
  my (@changed, @missing, @added, @ooo);
  my %max;   # 방언별로 잠긴 최대 버전
  for my $p (keys %{ $lock->{files} }) {
    my ($v, $ver) = (vendor_of($p), version_of($p));
    next unless $v && $ver;
    $max{$v} = $ver if !defined $max{$v} || $ver gt $max{$v};
  }
  for my $p (sort keys %{ $lock->{files} }) {
    if (!exists $disk->{$p}) { push @missing, $p; next; }
    push @changed, $p if $disk->{$p}{hash} ne $lock->{files}{$p};
  }
  for my $p (sort keys %$disk) {
    next if exists $lock->{files}{$p};
    my ($v, $ver) = ($disk->{$p}{vendor}, $disk->{$p}{version});
    if (defined $max{$v} && $ver lt $max{$v}) { push @ooo, $p; } else { push @added, $p; }
  }
  return { changed => \@changed, missing => \@missing, added => \@added, out_of_order => \@ooo };
}

sub report_frozen {
  my ($d) = @_;
  my @msg;
  for my $p (@{ $d->{changed} }) {
    push @msg, "  - 잠긴 마이그레이션의 내용이 바뀌었다: $p\n      이미 배포됐을 수 있는 마이그레이션은 고치지 않는다 — 새 V 파일을 추가하라 (./gradlew newMigration -Pname=<설명>). 정말 로컬 전용이라 고쳐야 한다면: $CMD --rewrite $p  (그리고 CHANGELOG 에 적는다)";
  }
  for my $p (@{ $d->{missing} }) {
    push @msg, "  - 잠긴 마이그레이션이 사라졌다: $p\n      이미 배포됐을 수 있는 마이그레이션은 지우지 않는다 — 새 V 파일을 추가하라. 정말 로컬 전용이라면: $CMD --rewrite $p  (그리고 CHANGELOG 에 적는다)";
  }
  return @msg;
}

sub report_new {
  my ($d) = @_;
  my @msg;
  push @msg, "  - 잠금에 없는 새 마이그레이션: $_\n      잠금에 추가하라: $CMD" for @{ $d->{added} };
  for my $p (@{ $d->{out_of_order} }) {
    push @msg, "  - out-of-order: $p 의 버전이 이미 잠긴 같은 방언의 최대 버전보다 작다 — 스켈레톤 자신의 파일은 순서대로 쌓는다 (앱이 outOfOrder 를 켜는 것은 앱의 선택). ./gradlew newMigration -Pname=<설명> 으로 새 버전을 받아 옮겨라";
  }
  return @msg;
}

sub run {
  my (@args) = @_;
  my ($root, $mode, @rewrite) = (abs_path(File::Spec->catdir(dirname(abs_path(__FILE__)), '..')), 'update');
  while (@args) {
    my $a = shift @args;
    if ($a eq '--root') { $root = abs_path(shift @args // die "--root 에 디렉토리가 필요하다\n") // die "--root: 없는 디렉토리\n"; }
    elsif ($a eq '--check') { $mode = 'check'; }
    elsif ($a eq '--regenerate') { $mode = 'regenerate'; }
    elsif ($a eq '--rewrite') { $mode = 'rewrite'; }
    elsif ($mode eq 'rewrite' && $a !~ /^--/) { push @rewrite, $a; }
    else { die "알 수 없는 인자: $a\n"; }
  }
  die "--rewrite 에는 경로가 하나 이상 필요하다\n" if $mode eq 'rewrite' && !@rewrite;

  my $disk = scan($root);

  if ($mode eq 'regenerate') {
    my $baseline = (sort map { $_->{version} } values %$disk)[-1] // die "마이그레이션 파일이 없다\n";
    write_lock($root, $baseline, { map { $_ => $disk->{$_}{hash} } keys %$disk });
    printf("%s: %d files locked, baseline %s\n", $LOCK, scalar(keys %$disk), $baseline);
    return 0;
  }

  my $lock = read_lock($root);
  my $d = compare($lock, $disk);

  if ($mode eq 'check') {
    my @msg = (report_frozen($d), report_new($d));
    if (@msg) {
      print STDERR "migrations.lock: " . scalar(@msg) . " problem(s)\n" . join("\n", @msg) . "\n";
      return 1;
    }
    printf("migrations lock ok — %d files, baseline %s\n", scalar(keys %{ $lock->{files} }), $lock->{baseline} // '-');
    return 0;
  }

  if ($mode eq 'rewrite') {
    my @bad;
    for my $p (@rewrite) {
      $p =~ s{^\./}{};
      my $touched = grep { $_ eq $p } @{ $d->{changed} }, @{ $d->{missing} };
      push @bad, "  - $p: 잠긴 파일이 아니거나 바뀐 것이 없다 (--rewrite 는 해시가 바뀐 파일 · 사라진 파일만 받는다)" unless $touched;
    }
    if (@bad) { print STDERR join("\n", @bad) . "\n"; return 1; }
    for my $p (@rewrite) {
      if (exists $disk->{$p}) { $lock->{files}{$p} = $disk->{$p}{hash}; print "rewrote $p\n"; }
      else { delete $lock->{files}{$p}; print "dropped $p (file is gone)\n"; }
    }
    write_lock($root, $lock->{baseline} // die("$LOCK 에 baseline 줄이 없다\n"), $lock->{files});
    return 0;
  }

  # update: 새 파일만 더한다
  my @frozen = report_frozen($d);
  if (@frozen || @{ $d->{out_of_order} }) {
    print STDERR "migrations.lock 을 갱신하지 않았다:\n" . join("\n", @frozen, report_new({ added => [], out_of_order => $d->{out_of_order} })) . "\n";
    return 1;
  }
  if (!@{ $d->{added} }) { print "nothing to add — $LOCK is up to date\n"; return 0; }
  $lock->{files}{$_} = $disk->{$_}{hash} for @{ $d->{added} };
  write_lock($root, $lock->{baseline} // die("$LOCK 에 baseline 줄이 없다\n"), $lock->{files});
  print "locked $_\n" for @{ $d->{added} };
  return 0;
}

exit(run(@ARGV)) unless caller;
1;

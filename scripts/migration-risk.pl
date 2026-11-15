#!/usr/bin/env perl
# 옛 버전을 깨는 마이그레이션 검사 (expand/contract). 후보 리비전 · 롤링 · 롤백 동안 새 버전과 옛 버전이 같은 DB 를 잠시 같이 쓴다 —
# 새 마이그레이션이 옛 버전의 쿼리를 깨면(없어진 컬럼 · 바뀐 이름 · 값을 못 넣는 NOT NULL) 그 구간에 오류가 난다. 그런 DDL 을 CI 에서 잡는다.
# 범위는 squawk · strong_migrations 가 잡는 것 중 작은 팀에 쓸모 있는 것만이다 (docs/schema-management.md 「Rolling deploys」).
#
#   perl scripts/migration-risk.pl            # 검사 (위험이 있으면 종료 코드 1) — CI 와 ./gradlew build 의 MigrationRiskTest 가 부른다
#   --root <디렉토리>                          # 레포 루트 (기본: 이 스크립트의 부모의 부모) — 테스트용
#
# 보는 파일: migrations.lock 의 baseline 보다 새 버전의 <루트>/**/src/main/resources/db/migration/<postgresql|mysql>/V<14자리>__*.sql.
# 기준선 이전 파일은 이미 모든 환경에 있다. new-project.sh 가 찍은 프로젝트의 기준선은 찍은 시점의 최신 버전이라 앱이 더하는 마이그레이션부터 본다.
# 통과시키기: 파일 안에 `-- allow-destructive: <이유>` (이유 필수). 의도한 contract 단계(옛 버전이 이미 내려간 뒤의 삭제)에 쓴다.
# 같은 파일에서 CREATE TABLE 한 표는 옛 버전이 모르므로 검사하지 않는다. 의존은 perl core 모듈뿐이다.
use strict;
use warnings;
use utf8;
use File::Basename qw(dirname);
use File::Find;
use File::Spec;
use Cwd qw(abs_path);

binmode(STDOUT, ':encoding(UTF-8)');
binmode(STDERR, ':encoding(UTF-8)');

my %SKIP = map { $_ => 1 } qw(build .gradle .git .kotlin node_modules out .claude);   # migrations-lock.pl 과 같다

# 규칙 이름 => 왜 위험한가 · 안전한 길
my %WHY = (
  'drop-table'          => '옛 버전이 아직 이 표를 읽고 쓴다. 코드가 표를 안 쓰게 된 릴리스가 나가고 옛 버전이 모두 내려간 뒤(contract)에 지운다',
  'drop-column'         => '옛 버전의 SELECT · INSERT 가 이 컬럼을 이름으로 부른다. 먼저 코드가 안 쓰게 배포하고, 옛 버전이 모두 내려간 뒤(contract)에 지운다',
  'rename-table'        => '옛 버전이 옛 이름으로 부른다. 새 표를 만들어(expand) 옮기고 코드를 바꾼 뒤 옛 표를 지운다(contract)',
  'rename-column'       => '옛 버전이 옛 이름으로 부른다. 새 컬럼을 더하고(expand) 값을 채워 코드를 옮긴 뒤 옛 컬럼을 지운다(contract)',
  'add-not-null-column' => '옛 버전의 INSERT 는 이 컬럼을 모른다 — 기본값이 없으면 실패한다. NULL 허용 또는 DEFAULT 로 더한다',
  'set-not-null'        => '옛 버전이 NULL 을 쓸 수 있다. 새 코드가 항상 채운다는 것이 옛 버전이 내려간 뒤에야 보장된다 — 그 뒤(contract)에 건다',
  'change-column-type'  => '옛 버전이 옛 타입으로 읽고 쓴다(좁히면 값이 잘리거나 실패한다). 새 컬럼을 더해 옮긴 뒤 옛 컬럼을 지운다. 넓히기만 한다면 이유를 적고 허용한다',
  'add-constraint'      => '옛 버전의 쓰기가 새 제약(UNIQUE · CHECK · FK · PK)을 어길 수 있다. 새 코드가 지킨다는 것이 보장된 뒤에 건다',
  'truncate'            => '표의 데이터를 전부 지운다 — 옛 버전과 무관하게 되돌릴 수 없다',
);

# 주석을 공백으로, 문자열 · 따옴표 식별자를 단순화한 텍스트 (줄 수는 그대로 — 줄 번호를 위해)
sub clean {
  my ($t) = @_;
  my $o = '';
  my $n = length $t;
  my $i = 0;
  while ($i < $n) {
    my $c = substr($t, $i, 1);
    my $two = substr($t, $i, 2);
    if ($two eq '--') {
      $i++ while $i < $n && substr($t, $i, 1) ne "\n";
    } elsif ($two eq '/*') {
      my $e = index($t, '*/', $i + 2);
      $e = $n - 2 if $e < 0;
      my $chunk = substr($t, $i, $e + 2 - $i);
      $o .= join('', $chunk =~ /\n/g);
      $i = $e + 2;
    } elsif ($c eq "'") {
      my $j = $i + 1;
      while ($j < $n) {
        if (substr($t, $j, 1) eq "'") { if (substr($t, $j + 1, 1) eq "'") { $j += 2; next; } last; }
        $j++;
      }
      $o .= "''" . join('', substr($t, $i, $j + 1 - $i) =~ /\n/g);
      $i = $j + 1;
    } elsif ($c eq '`' || $c eq '"') {
      my $e = index($t, $c, $i + 1);
      $e = $n - 1 if $e < 0;
      $o .= substr($t, $i + 1, $e - $i - 1);
      $i = $e + 1;
    } else {
      $o .= $c;
      $i++;
    }
  }
  return $o;
}

# [ [시작 줄, 정규화한 문장(소문자 · 공백 하나)] ... ]
sub statements {
  my ($t) = @_;
  my @out;
  my ($buf, $depth, $line, $start) = ('', 0, 1, undef);
  for my $c (split //, $t) {
    if ($c eq "\n") { $line++; $buf .= ' '; next; }
    if ($c eq ';' && $depth <= 0) {
      push @out, [$start, norm($buf)] if defined $start;
      ($buf, $depth, $start) = ('', 0, undef);
      next;
    }
    $depth++ if $c eq '(';
    $depth-- if $c eq ')';
    $start = $line if !defined $start && $c !~ /\s/;
    $buf .= $c;
  }
  push @out, [$start, norm($buf)] if defined $start;
  return @out;
}

sub norm { my ($s) = @_; $s =~ s/\s+/ /g; $s =~ s/^ | $//g; return lc $s; }

sub bare { my ($n) = @_; $n =~ s/.*\.//; return $n; }   # schema.table → table

# 최상위 쉼표로 가른다
sub split_actions {
  my ($s) = @_;
  my (@a, $cur, $d);
  ($cur, $d) = ('', 0);
  for my $c (split //, $s) {
    if ($c eq ',' && $d == 0) { push @a, $cur; $cur = ''; next; }
    $d++ if $c eq '(';
    $d-- if $c eq ')';
    $cur .= $c;
  }
  push @a, $cur if $cur =~ /\S/;
  return map { my $x = $_; $x =~ s/^\s+|\s+$//g; $x } @a;
}

# 한 파일의 문장들 → [ [줄, 규칙], ... ]
sub findings {
  my (@stmts) = @_;
  my %created;
  for my $s (@stmts) {
    $created{ bare($1) } = 1 if $s->[1] =~ /^create (?:(?:global |local )?(?:temporary |temp |unlogged )?)table (?:if not exists )?(\S+)/;
  }
  # 이 파일에서 더한 NULL 허용 · 기본값 없는 컬럼 — 옛 버전은 쓰지 않아 NULL 뿐이고, NULL 은 UNIQUE 에서 서로 다르다
  my %newcol;
  for my $s (@stmts) {
    next unless $s->[1] =~ /^alter table (?:if exists )?(?:only )?(\S+) (.*)$/;
    my ($t, $rest) = ($1, $2);
    for my $a (split_actions($rest)) {
      next unless $a =~ /^add (?:column )?(?:if not exists )?([a-z_][a-z0-9_]*) /;
      my $c = $1;
      next if $c =~ /^(?:constraint|unique|check|foreign|primary|index|key|fulltext|spatial|partition)$/;
      $newcol{ bare($t) . ".$c" } = 1 if $a !~ /\bnot null\b|\bdefault\b|\bgenerated\b|\bauto_increment\b/;
    }
  }
  my $unique_on_new = sub {
    my ($t, $text) = @_;
    return 0 unless $text =~ /\(([^)]*)\)/;
    return scalar grep { my ($c) = /^\s*([a-z_][a-z0-9_]*)/; defined $c && $newcol{ bare($t) . ".$c" } } split /,/, $1;
  };
  my @f;
  for my $s (@stmts) {
    my ($line, $q) = @$s;
    if ($q =~ /^drop table\b/) { push @f, [$line, 'drop-table']; next; }
    if ($q =~ /^truncate\b/) { push @f, [$line, 'truncate']; next; }
    if ($q =~ /^rename table\b/) { push @f, [$line, 'rename-table']; next; }
    if ($q =~ /^create unique index (?:concurrently )?(?:if not exists )?\S+ on (?:only )?(\S+)/) {
      my $t = $1;
      push @f, [$line, 'add-constraint'] unless $created{ bare($t) } || $unique_on_new->($t, $q);
      next;
    }
    next unless $q =~ /^alter table (?:if exists )?(?:only )?(\S+) (.*)$/;
    my ($table, $rest) = ($1, $2);
    next if $created{ bare($table) };
    for my $a (split_actions($rest)) {
      if ($a =~ /^drop (?!constraint\b|index\b|key\b|primary\b|foreign\b|check\b|partition\b)/) { push @f, [$line, 'drop-column']; }
      elsif ($a =~ /^rename (?:to|as)\b/) { push @f, [$line, 'rename-table']; }
      elsif ($a =~ /^rename (?!constraint\b|index\b|key\b)/) { push @f, [$line, 'rename-column']; }
      elsif ($a =~ /^change (?:column )?(\S+) (\S+) /) { push @f, [$line, $1 eq $2 ? 'change-column-type' : 'rename-column']; }
      elsif ($a =~ /^modify\b/) { push @f, [$line, 'change-column-type']; }
      elsif ($a =~ /^alter (?:column )?\S+ (?:set data )?type\b/) { push @f, [$line, 'change-column-type']; }
      elsif ($a =~ /^alter (?:column )?\S+ set not null\b/) { push @f, [$line, 'set-not-null']; }
      elsif ($a =~ /^add (?:constraint|unique|check|foreign|primary)\b/) {
        my $is_unique = $a =~ /^add (?:constraint \S+ )?unique\b/;
        push @f, [$line, 'add-constraint'] unless $is_unique && $unique_on_new->($table, $a);
      }
      elsif ($a =~ /^add (?:index|key|fulltext|spatial|partition)\b/) { next; }
      elsif ($a =~ /^add\b/) {
        push @f, [$line, 'add-not-null-column']
          if $a =~ /\bnot null\b/ && $a !~ /\bdefault\b/ && $a !~ /\bgenerated\b|\bauto_increment\b|\bserial\b|\bbigserial\b|\bsmallserial\b/;
      }
    }
  }
  return @f;
}

sub slurp {
  my ($path) = @_;
  open(my $fh, '<:encoding(UTF-8)', $path) or die "읽을 수 없다: $path ($!)\n";
  local $/;
  my $d = <$fh>;
  close($fh);
  return $d;
}

sub baseline_of {
  my ($root) = @_;
  my $path = "$root/migrations.lock";
  die "migrations.lock 이 없다 — 기준선을 알 수 없다: perl scripts/migrations-lock.pl --regenerate\n" unless -f $path;
  for my $line (split /\n/, slurp($path)) { return $1 if $line =~ /^baseline\s+(\d{14})\s*$/; }
  die "migrations.lock 에 baseline 줄이 없다\n";
}

sub scan {
  my ($root, $baseline) = @_;
  my @files;
  find({
    no_chdir => 1,
    preprocess => sub { return sort @_ },
    wanted => sub {
      my $path = $File::Find::name;
      if (-d $path) {
        return if $path eq $root;
        my $name = (File::Spec->splitdir($path))[-1];
        if ($SKIP{$name} || -e "$path/.git") { $File::Find::prune = 1; }
        return;
      }
      my $rel = File::Spec->abs2rel($path, $root);
      $rel =~ s{\\}{/}g;
      return unless $rel =~ m{(?:^|/)src/main/resources/db/migration/(?:postgresql|mysql)/V(\d{14})__[^/]+\.sql$};
      push @files, $rel if $1 gt $baseline;
    },
  }, $root);
  return sort @files;
}

sub run {
  my (@args) = @_;
  my $root = abs_path(File::Spec->catdir(dirname(abs_path(__FILE__)), '..'));
  while (@args) {
    my $a = shift @args;
    if ($a eq '--root') { $root = abs_path(shift @args // die "--root 에 디렉토리가 필요하다\n") // die "--root: 없는 디렉토리\n"; }
    elsif ($a eq '--check') { }
    else { die "알 수 없는 인자: $a\n"; }
  }
  my $baseline = baseline_of($root);
  my @files = scan($root, $baseline);
  my @problems;
  for my $rel (@files) {
    my $raw = slurp("$root/$rel");
    if ($raw =~ /--\s*allow-destructive:[ \t]*(\S[^\n]*)/) { next; }
    my $bad_marker = $raw =~ /--\s*allow-destructive:/;
    for my $f (findings(statements(clean($raw)))) {
      my ($line, $rule) = @$f;
      push @problems, "  - $rel:$line [$rule] $WHY{$rule}" . ($bad_marker ? "\n      allow-destructive 표시에 이유가 없다 — `-- allow-destructive: <이유>` 로 이유를 적어야 통과한다" : '');
    }
  }
  if (@problems) {
    print STDERR "migration-risk: " . scalar(@problems) . " problem(s) — 새 버전과 옛 버전이 같은 DB 를 같이 쓰는 동안(후보 리비전 · 롤링 · 롤백) 옛 버전을 깬다\n"
      . join("\n", @problems) . "\n"
      . "  의도한 것이면(contract 단계 · 아직 아무도 안 쓰는 표 · 넓히기) 그 파일에 `-- allow-destructive: <이유>` 한 줄을 적는다. expand/contract 절차: docs/schema-management.md 「Rolling deploys」\n";
    return 1;
  }
  printf("migration risk ok — %d file(s) newer than baseline %s\n", scalar(@files), $baseline);
  return 0;
}

exit(run(@ARGV)) unless caller;
1;

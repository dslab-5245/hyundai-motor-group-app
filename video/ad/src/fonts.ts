import {loadFont} from '@remotion/fonts';
import {staticFile} from 'remotion';

const weights: Array<[string, string]> = [
  ['400', 'Pretendard-Regular.otf'],
  ['500', 'Pretendard-Medium.otf'],
  ['600', 'Pretendard-SemiBold.otf'],
  ['700', 'Pretendard-Bold.otf'],
  ['800', 'Pretendard-ExtraBold.otf'],
];

let loading: Promise<void> | null = null;
export const ensureFonts = (): Promise<void> => {
  if (!loading) {
    loading = Promise.all(
      weights.map(([weight, file]) =>
        loadFont({family: 'Pretendard', url: staticFile(`fonts/${file}`), weight, format: 'opentype'}).catch(() => undefined),
      ),
    ).then(() => undefined);
  }
  return loading;
};

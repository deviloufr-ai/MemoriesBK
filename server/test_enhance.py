import numpy as np

from enhance import enhance_rgb, load_rgb, save_jpeg


def test_dark_bluish_photo_gets_brighter_and_more_neutral(tmp_path):
    rng = np.random.default_rng(0)
    # photo sous-exposée avec une dominante bleue
    img = np.clip(rng.normal([40, 45, 80], 12, size=(240, 320, 3)), 0, 255).astype(np.uint8)
    out = enhance_rgb(img)
    assert out.shape == img.shape and out.dtype == np.uint8
    assert out.mean() > img.mean() + 5
    cast = lambda x: x[..., 2].mean() - x[..., 0].mean()  # bleu - rouge
    assert cast(out) < cast(img)

    save_jpeg(out, tmp_path / "out.jpg")
    assert load_rgb(tmp_path / "out.jpg").shape == img.shape


def test_quality_detects_blur_and_darkness(tmp_path):
    import cv2

    from enhance import quality

    rng = np.random.default_rng(1)
    sharp = rng.integers(0, 255, size=(600, 800, 3), dtype=np.uint8)
    cv2.imwrite(str(tmp_path / "sharp.png"), sharp)
    cv2.imwrite(str(tmp_path / "blur.png"), cv2.GaussianBlur(sharp, (0, 0), 6))
    cv2.imwrite(str(tmp_path / "dark.png"), (sharp * 0.1).astype(np.uint8))
    s_sharp, b_sharp = quality(tmp_path / "sharp.png")
    s_blur, _ = quality(tmp_path / "blur.png")
    _, b_dark = quality(tmp_path / "dark.png")
    assert s_blur < s_sharp / 10
    assert b_dark < 30 < b_sharp

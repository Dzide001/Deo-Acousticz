# Measurement data attribution

`JBLPro_PRX635_H_IR.mat.zip` contains the unchanged `JBLPro_PRX635_H_IR.mat` measurement
file from Princeton University's 3D Audio and Applied Acoustics (3D3A) Laboratory.
It was extracted from the public **3D3A Directivity Survey.zip** archive and recompressed;
this small ZIP is not the original complete upstream archive.

Source: https://3d3a.princeton.edu/loudspeaker-directivity-ongoing-experimental-survey
Speaker/setup: https://3d3a.princeton.edu/jbl-professional-prx635
Manual: https://3d3a.princeton.edu/sites/g/files/toruqf931/files/documents/3D3A_Directivity_Database_0.pdf
Upstream archive: https://drive.google.com/file/d/1HnpqJeTl96fs-QMKp6D8Ae07FakNe4Nz/view
License: **Creative Commons Attribution 4.0 International** (as declared by the database page)
https://creativecommons.org/licenses/by/4.0/
Retrieved 2026-11-18. Source-file and upstream-archive SHA-256 values are pinned in `../protocol.json`.

The database page credits Lukasz Mosakowski, Tim Matchen, Joe Tylka, Rahulram Sridhar,
Tony Jin and Gianfranco Colombi under Prof. Edgar Choueiri. Cite J. G. Tylka,
R. Sridhar and E. Y. Choueiri, “A Database of Loudspeaker Polar Radiation Measurements,”
AES 139th Convention, Engineering Brief 230, 2015. The associated papers/manual are
linked for documentation; their text is not included under the dataset license here.

Changes: the original MAT bytes are unchanged. Derived JSON fixtures recompute finite-band
magnitudes with an explicit time window and create disjoint angular training/holdout subsets.
They are not Princeton's published smoothed plots, new laboratory measurements or manufacturer
certification. Neither Princeton, JBL nor the data authors endorse this application.
